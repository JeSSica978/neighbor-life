package loadtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import io.lettuce.core.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.sql.*;
import java.time.*;
import java.util.*;
import static loadtest.LoadtestEnvironment.*;

/** Opt-in fixture only; a dry-run has no network calls and creates no user/coupon/manifest. */
public final class LoadtestFixture {
    public static void main(String[] raw) {
        try {
            require(raw.length > 0, "Action prepare/reset/cleanup/inspect required");
            Map<String, String> args = arguments(raw);
            Set<String> allowed = new HashSet<>(Arrays.asList("run-id", "execute", "users", "vouchers", "stock", "expected-attempts"));
            require(allowed.containsAll(args.keySet()), "Unknown fixture option");
            String action = raw[0]; require(Arrays.asList("prepare", "reset", "cleanup", "inspect").contains(action), "Unknown fixture action");
            try (LoadtestEnvironment env = new LoadtestEnvironment(args)) {
                int users = positive(args, "users", 100), coupons = positive(args, "vouchers", 1);
                int attempts = positive(args, "expected-attempts", users);
                int minimumStock = (int) Math.ceil(attempts * 1.2);
                int stock = positive(args, "stock", minimumStock);
                require(users >= attempts && stock >= minimumStock && users <= 100000 && coupons <= 100, "User/stock capacity or safety limit invalid");
                if (!args.containsKey("execute")) {
                    System.out.println("DRY_RUN action=" + action + " runId=" + env.runId + " users=" + users + " vouchers=" + coupons + " stock=" + stock);
                    System.out.println("No connections/writes. Reset allocates fresh voucher IDs; cleanup requires inactive drained consumer and ownership checks.");
                    return;
                }
                Files.createDirectories(env.directory);
                // Serialize all fixture mutations for this isolated environment, including separate runIds.
                try (FileChannel channel = FileChannel.open(env.directory.getParent().resolve(".fixture.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                     FileLock lock = channel.tryLock()) {
                    require(lock != null, "Another fixture mutation is active");
                    env.connect(!action.equals("inspect"));
                    if (action.equals("prepare")) prepare(env, users, coupons, stock, attempts);
                    else {
                        ObjectNode manifest = env.readManifest();
                        if (action.equals("cleanup")) cleanup(env, manifest);
                        else {
                            require("READY".equals(manifest.path("state").asText()), "Fixture is incomplete/retired; preserve journal for recovery");
                            env.verifyOwnership(manifest);
                            if (action.equals("reset")) reset(env, manifest);
                            else {
                                ObjectNode result = env.consistency(manifest);
                                JSON.writerWithDefaultPrettyPrinter().writeValue(env.directory.resolve("consistency.json").toFile(), result);
                                System.out.println("INSPECT runId=" + env.runId + " drainedInvariant=" + result.path("drainedInvariant").asBoolean());
                            }
                        }
                    }
                }
            }
        } catch (Exception e) { reportError(e); System.exit(1); }
    }
    private static int positive(Map<String, String> args, String name, int fallback) {
        int value = Integer.parseInt(args.getOrDefault(name, String.valueOf(fallback))); require(value > 0, "Counts must be positive"); return value;
    }
    private static void prepare(LoadtestEnvironment env, int count, int couponCount, int stock, int attempts) throws Exception {
        require(!Files.exists(env.manifestFile), "RunId already used; never overwrite an existing fixture");
        ObjectNode broker = env.kafka();
        require(broker.path("activeMembers").asInt() == 1, "Expected run-scoped Consumer must already be active before preparation");
        require(env.outbox().path("xlen").asLong() == 0, "New run outbox is not empty");
        ObjectNode manifest = JSON.createObjectNode(); String marker = "LT_" + env.runId;
        manifest.put("runId", env.runId); manifest.put("createdAt", Instant.now().toString()); manifest.put("marker", marker);
        manifest.set("environment", env.description()); manifest.put("state", "PREPARING"); manifest.put("cleanupState", "NOT_STARTED");
        manifest.put("initialStock", stock); manifest.put("expectedUniqueAttempts", attempts); manifest.put("generation", 0);
        manifest.putArray("users"); manifest.putArray("vouchers"); manifest.putArray("plannedPhones");
        manifest.putArray("redisKeys"); manifest.put("tokenCsv", env.directory.resolve("tokens.csv").toString());
        manifest.set("unownedBaselineSha256", env.fingerprints(manifest)); env.save(manifest);
        SecureRandom random = new SecureRandom(); Set<String> planned = new HashSet<>(); String firstToken = null;
        Path csv = env.directory.resolve("tokens.csv"); Files.write(csv, new byte[0], StandardOpenOption.CREATE_NEW);
        for (int i = 0; i < count; i++) {
            String phone;
            do { phone = "199" + String.format("%08d", random.nextInt(100000000)); }
            while (planned.contains(phone) || env.scalar("SELECT COUNT(*) FROM tb_user WHERE phone=?", phone) != 0);
            planned.add(phone); ((ArrayNode) manifest.path("plannedPhones")).add(phone); env.save(manifest);
            env.request("POST", "/user/code?phone=" + phone, null, null);
            String code = env.redis.sync().get("login:code:" + phone);
            require(code != null && code.matches("[0-9]{6}"), "OTP absent from isolated Redis; backend may be misconfigured");
            ObjectNode login = JSON.createObjectNode().put("phone", phone).put("code", code);
            String token = env.request("POST", "/user/login", login, null).asText(); require(token.matches("[A-Za-z0-9_-]{16,128}"), "Unexpected token format");
            JsonNode me = env.request("GET", "/user/me", null, token); long user = me.path("id").asLong();
            require(user >= 1000000 && env.scalar("SELECT COUNT(*) FROM tb_user WHERE id=? AND phone=?", user, phone) == 1,
                    "Login identity does not belong to dedicated MySQL");
            require(String.valueOf(user).equals(env.redis.sync().hget("login:token:" + token, "id")), "Session belongs to another Redis");
            ObjectNode row = ((ArrayNode) manifest.path("users")).addObject(); row.put("id", user); row.put("phone", phone); env.save(manifest);
            Files.write(csv, (user + "," + token + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
            if (firstToken == null) firstToken = token;
        }
        long typeId;
        String typeName = "LT_TYPE_" + UUID.nameUUIDFromBytes(env.runId.getBytes(StandardCharsets.UTF_8)).toString().replace("-", "").substring(0, 24);
        manifest.put("typeName", typeName); env.save(manifest);
        try (PreparedStatement ps = env.db.prepareStatement("INSERT INTO tb_shop_type(name,icon,sort) VALUES(?,'',0)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, typeName); require(ps.executeUpdate() == 1, "Category creation failed");
            try (ResultSet rs = ps.getGeneratedKeys()) { require(rs.next(), "Category generated ID missing"); typeId = rs.getLong(1); }
        }
        safeId(typeId, true); manifest.put("typeId", typeId); env.save(manifest);
        ObjectNode shop = JSON.createObjectNode().put("name", marker).put("typeId", typeId).put("images", "").put("address", "独立压测数据")
                .put("x", 120).put("y", 30).put("sold", 0).put("comments", 0).put("score", 40).put("openHours", "00:00-23:59");
        long shopId = env.request("POST", "/shop", shop, firstToken).asLong(); safeId(shopId, true);
        manifest.put("shopId", shopId); env.save(manifest);
        for (int i = 0; i < couponCount; i++) {
            String title = marker + "_g0_" + i;
            manifest.put("creatingVoucherTitle", title); env.save(manifest);
            ObjectNode coupon = JSON.createObjectNode().put("shopId", shopId).put("title", title).put("subTitle", "仅限独立测试，不接入支付")
                    .put("rules", "每人每券一次").put("type", 1).put("status", 1).put("payValue", 8000).put("actualValue", 10000)
                    .put("stock", stock).put("beginTime", LocalDateTime.now().minusMinutes(5).toString())
                    .put("endTime", LocalDateTime.now().plusDays(1).toString());
            long id = env.request("POST", "/voucher/seckill", coupon, firstToken).asLong(); safeId(id, false);
            addCoupon(manifest, id, title, stock, 0, i); env.save(manifest);
        }
        manifest.remove("creatingVoucherTitle"); manifest.put("state", "READY"); env.verifyOwnership(manifest);
        ObjectNode check = env.consistency(manifest);
        for (JsonNode coupon : check.path("vouchers")) require(coupon.path("orderCount").asInt() == 0 && coupon.path("drainedInvariant").asBoolean(), "Initial inventory mismatch");
        // A newly started group with no records may have no committed offset yet: initial SQL/Redis invariants are the readiness check.
        manifest.set("initialConsistency", check); manifest.put("tokenCsvSha256", hash(csv)); env.save(manifest);
        printSummary("PREPARED", env, manifest);
    }
    private static void addCoupon(ObjectNode manifest, long id, String title, int stock, int generation, int slot) {
        ObjectNode row = ((ArrayNode) manifest.path("vouchers")).addObject();
        row.put("id", id); row.put("title", title); row.put("initialStock", stock); row.put("generation", generation); row.put("slot", slot); row.put("active", true);
        ((ArrayNode) manifest.path("redisKeys")).add("seckill:stock:" + id).add("seckill:order:" + id);
    }
    private static void reset(LoadtestEnvironment env, ObjectNode manifest) throws Exception {
        env.requireBackendStopped(); require(env.consistency(manifest).path("drainedInvariant").asBoolean(), "Do not reset inconsistent inventory");
        verifyAcceptedOrders(env, manifest);
        int generation = manifest.path("generation").asInt() + 1;
        List<JsonNode> active = new ArrayList<>(); for (JsonNode voucher : manifest.path("vouchers")) if (voucher.path("active").asBoolean()) active.add(voucher.deepCopy());
        manifest.put("state", "RESETTING"); manifest.put("resetGeneration", generation); env.save(manifest);
        // Old vouchers/orders remain immutable so replayed old messages retain their idempotency records.
        for (JsonNode old : active) {
            String title = manifest.path("marker").asText() + "_g" + generation + "_" + old.path("slot").asInt();
            manifest.put("creatingVoucherTitle", title); env.save(manifest);
            long id;
            env.db.setAutoCommit(false);
            try {
                try (PreparedStatement ps = env.db.prepareStatement("INSERT INTO tb_voucher(shop_id,title,sub_title,rules,pay_value,actual_value,type,status) SELECT shop_id,?,sub_title,rules,pay_value,actual_value,type,status FROM tb_voucher WHERE id=?", Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, title); ps.setLong(2, old.path("id").asLong()); require(ps.executeUpdate() == 1, "Coupon clone failed");
                    try (ResultSet rs = ps.getGeneratedKeys()) { require(rs.next(), "Coupon generated ID missing"); id = rs.getLong(1); }
                }
                safeId(id, false);
                env.execute("INSERT INTO tb_seckill_voucher(voucher_id,stock,begin_time,end_time) VALUES(?,?,?,?)", id, old.path("initialStock").asInt(), Timestamp.valueOf(LocalDateTime.now().minusMinutes(5)), Timestamp.valueOf(LocalDateTime.now().plusDays(1)));
                env.db.commit();
            } catch (Exception e) { env.db.rollback(); throw e; } finally { env.db.setAutoCommit(true); }
            addCoupon(manifest, id, title, old.path("initialStock").asInt(), generation, old.path("slot").asInt()); env.save(manifest);
            require("OK".equals(env.redis.sync().set("seckill:stock:" + id, old.path("initialStock").asText(), SetArgs.Builder.nx())), "New coupon Redis initialization failed; preserve RESETTING journal");
        }
        for (JsonNode voucher : manifest.path("vouchers")) if (voucher.path("generation").asInt() != generation) ((ObjectNode) voucher).put("active", false);
        manifest.remove("creatingVoucherTitle"); manifest.put("generation", generation); manifest.put("state", "READY"); env.save(manifest);
        printSummary("RESET_FRESH_IDS", env, manifest);
    }
    private static void cleanup(LoadtestEnvironment env, ObjectNode manifest) throws Exception {
        if ("CLEANED".equals(manifest.path("state").asText())) { System.out.println("ALREADY_CLEANED runId=" + env.runId); return; }
        env.requireBackendStopped(); String stage = manifest.path("cleanupState").asText();
        if ("NOT_STARTED".equals(stage)) {
            require("READY".equals(manifest.path("state").asText()), "Incomplete prepare/reset needs manual journal recovery; no blind cleanup");
            env.verifyOwnership(manifest);
            ObjectNode before = env.consistency(manifest); require(before.path("drainedInvariant").asBoolean(), "Cleanup blocked by consistency failure");
            verifyAcceptedOrders(env, manifest);
            require(env.fingerprints(manifest).equals(manifest.path("unownedBaselineSha256")), "Unowned table fingerprints changed; preserve evidence");
            manifest.set("preCleanupConsistency", before);
            ArrayNode reservations = manifest.putArray("cleanupReservationKeys");
            for (String key : env.reservations(ids(manifest.path("vouchers"), "id"), false)) reservations.add(key);
            manifest.put("cleanupState", "DB_DELETE_INTENT"); manifest.put("state", "CLEANING"); env.save(manifest);
            stage = "DB_DELETE_INTENT";
        }
        if ("DB_DELETE_INTENT".equals(stage)) {
            long shop = manifest.path("shopId").asLong();
            long remaining = env.scalar("SELECT COUNT(*) FROM tb_shop WHERE id=?", shop);
            remaining += env.scalar("SELECT COUNT(*) FROM tb_shop_type WHERE id=?", manifest.path("typeId").asLong());
            for (JsonNode coupon : manifest.path("vouchers")) remaining += env.scalar("SELECT COUNT(*) FROM tb_voucher WHERE id=?", coupon.path("id").asLong());
            for (JsonNode user : manifest.path("users")) remaining += env.scalar("SELECT COUNT(*) FROM tb_user WHERE id=?", user.path("id").asLong());
            if (remaining != 0) {
                env.verifyOwnership(manifest); require(env.consistency(manifest).path("drainedInvariant").asBoolean(), "Data changed after cleanup intent");
                env.db.setAutoCommit(false);
                try {
                    for (JsonNode coupon : manifest.path("vouchers")) {
                        long id = coupon.path("id").asLong(); safeId(id, false);
                        env.execute("DELETE FROM tb_voucher_order WHERE voucher_id=?", id);
                        require(env.execute("DELETE FROM tb_seckill_voucher WHERE voucher_id=?", id) == 1, "Owned inventory delete mismatch");
                        require(env.execute("DELETE FROM tb_voucher WHERE id=? AND shop_id=? AND title=?", id, shop, coupon.path("title").asText()) == 1, "Owned coupon delete mismatch");
                    }
                    require(env.execute("DELETE FROM tb_shop WHERE id=? AND name=?", shop, manifest.path("marker").asText()) == 1, "Owned shop delete mismatch");
                    require(env.execute("DELETE FROM tb_shop_type WHERE id=? AND name=?", manifest.path("typeId").asLong(), manifest.path("typeName").asText()) == 1, "Owned category delete mismatch");
                    for (JsonNode user : manifest.path("users")) require(env.execute("DELETE FROM tb_user WHERE id=? AND phone=?", user.path("id").asLong(), user.path("phone").asText()) == 1, "Owned user delete mismatch");
                    env.db.commit();
                } catch (Exception e) { env.db.rollback(); throw e; } finally { env.db.setAutoCommit(true); }
            }
            // Recovery after a commit but before manifest save must also prove no dependent rows remain.
            for (JsonNode coupon : manifest.path("vouchers")) require(env.scalar("SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=?", coupon.path("id").asLong()) == 0
                    && env.scalar("SELECT COUNT(*) FROM tb_seckill_voucher WHERE voucher_id=?", coupon.path("id").asLong()) == 0, "Orphan owned rows remain");
            manifest.put("cleanupState", "DB_CLEANED"); env.save(manifest); stage = "DB_CLEANED";
        }
        if ("DB_CLEANED".equals(stage)) {
            Set<Long> users = ids(manifest.path("users"), "id"), coupons = ids(manifest.path("vouchers"), "id");
            for (JsonNode key : manifest.path("cleanupReservationKeys")) {
                Map<String, String> reservation = env.redis.sync().hgetall(key.asText());
                if (!reservation.isEmpty()) require(coupons.contains(Long.valueOf(reservation.getOrDefault("voucherId", "-1")))
                        && users.contains(Long.valueOf(reservation.getOrDefault("userId", "-1"))), "Reservation ownership mismatch");
                env.redis.sync().del(key.asText());
            }
            for (JsonNode coupon : manifest.path("vouchers")) {
                long id = coupon.path("id").asLong(); safeId(id, false); env.redis.sync().del("seckill:stock:" + id, "seckill:order:" + id);
            }
            // Only sessions whose Redis identity belongs to manifest-owned users are removed.
            ScanCursor cursor = ScanCursor.INITIAL;
            do {
                KeyScanCursor<String> batch = env.redis.sync().scan(cursor, ScanArgs.Builder.matches("login:token:*").limit(500));
                for (String key : batch.getKeys()) if (users.contains(Long.valueOf(Objects.toString(env.redis.sync().hget(key, "id"), "-1")))) env.redis.sync().del(key);
                cursor = batch;
            } while (!cursor.isFinished());
            for (JsonNode user : manifest.path("users")) env.redis.sync().del("login:code:" + user.path("phone").asText());
            long shop = manifest.path("shopId").asLong(); safeId(shop, true); env.redis.sync().del("cache:shop:" + shop, "cache:voucher:list:" + shop);
            require(env.fingerprints(manifest).equals(manifest.path("unownedBaselineSha256")), "Unowned fingerprint mismatch after cleanup");
            manifest.put("cleanupState", "COMPLETE"); manifest.put("state", "CLEANED"); manifest.put("cleanedAt", Instant.now().toString()); env.save(manifest);
            // Retain the private CSV/JTL as evidence. Topic/group/offset/outbox and ID sequences are never deleted/reset.
        }
        System.out.println("CLEANED runId=" + env.runId + "; keep topic/group offsets; never restart a cleaned run");
    }
    private static String hash(Path path) throws Exception {
        StringBuilder hex = new StringBuilder(); for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))) hex.append(String.format("%02x", b & 255)); return hex.toString();
    }
    private static void verifyAcceptedOrders(LoadtestEnvironment env, ObjectNode manifest) throws Exception {
        Path jtl = env.directory.resolve("results.jtl");
        if (!Files.exists(jtl)) return; // A prepared fixture can be cleaned without ever injecting requests.
        List<String> lines = Files.readAllLines(jtl, StandardCharsets.UTF_8);
        require(!lines.isEmpty(), "Empty JTL cannot establish acceptance completeness");
        List<String> header = LoadtestObserver.csvFields(lines.get(0));
        require(header.containsAll(Arrays.asList("label", "business_success", "orderId", "userId")), "Safe JTL acceptance fields missing");
        Set<Long> coupons = ids(manifest.path("vouchers"), "id"), users = ids(manifest.path("users"), "id"), seen = new HashSet<>();
        Map<Long, Long> accepted = new LinkedHashMap<>();
        for (int i = 1; i < lines.size(); i++) {
            List<String> row = LoadtestObserver.csvFields(lines.get(i)); require(row.size() == header.size(), "Malformed JTL");
            if (!"SECKILL_ACCEPT".equals(row.get(header.indexOf("label"))) || !"true".equals(row.get(header.indexOf("business_success")))) continue;
            long id = Long.parseLong(row.get(header.indexOf("orderId"))), user = Long.parseLong(row.get(header.indexOf("userId")));
            require(users.contains(user) && seen.add(id), "Accepted order ID repeated or user unowned"); accepted.put(id, user);
        }
        List<Long> ordered = new ArrayList<>(accepted.keySet());
        for (int i = 0; i < ordered.size(); i += 500) {
            List<Long> batch = ordered.subList(i, Math.min(i + 500, ordered.size())); int visible = 0;
            try (PreparedStatement ps = env.statement("SELECT id,user_id,voucher_id FROM tb_voucher_order WHERE id IN (" + marks(batch.size()) + ")", batch.toArray()); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) { require(accepted.get(rs.getLong(1)) == rs.getLong(2) && coupons.contains(rs.getLong(3)), "Accepted order identity mismatch"); visible++; }
            }
            require(visible == batch.size(), "HTTP accepted order is not in MySQL; cleanup blocked");
        }
    }
    private static void printSummary(String action, LoadtestEnvironment env, ObjectNode manifest) {
        List<Long> active = new ArrayList<>(); for (JsonNode coupon : manifest.path("vouchers")) if (coupon.path("active").asBoolean()) active.add(coupon.path("id").asLong());
        System.out.println(action + " runId=" + env.runId + " activeVoucherIds=" + active + " users=" + manifest.path("users").size() + "; tokens remain private");
    }
}
