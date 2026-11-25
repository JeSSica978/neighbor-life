package loadtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static loadtest.LoadtestEnvironment.*;

/** Read-only primary-key sampling. No Kafka poll, commit, business request, or stock write. */
public final class LoadtestObserver {
    static final class Accepted {
        long id, start, accepted, discovered, user;
        Accepted(long id, long start, long accepted, long discovered, long user) {
            this.id=id; this.start=start; this.accepted=accepted; this.discovered=discovered; this.user=user;
        }
    }
    public static void main(String[] raw) {
        try {
            require(raw.length > 0 && raw[0].equals("observe"), "Observer action must be observe");
            Map<String,String> args=arguments(raw);
            require(new HashSet<>(Arrays.asList("run-id","execute","duration-seconds","poll-ms")).containsAll(args.keySet()), "Unknown observer option");
            int duration=Integer.parseInt(args.getOrDefault("duration-seconds","250"));
            int poll=Integer.parseInt(args.getOrDefault("poll-ms","250"));
            require(duration > 0 && duration <= 3600 && poll >= 250 && poll <= 10000, "Observer duration/poll outside safety limits");
            try (LoadtestEnvironment env=new LoadtestEnvironment(args)) {
                if (!args.containsKey("execute")) { System.out.println("DRY_RUN observer runId="+env.runId+" pollMs="+poll+"; no connections"); return; }
                ObjectNode manifest=env.readManifest(); require("READY".equals(manifest.path("state").asText()), "Observer requires READY owned fixture");
                env.connect(false); env.verifyOwnership(manifest);
                observe(env,manifest,poll,duration);
            }
        } catch(Exception e) { reportError(e); System.exit(1); }
    }
    private static void observe(LoadtestEnvironment env,ObjectNode manifest,int poll,int duration) throws Exception {
        Path jtl=env.directory.resolve("results.jtl");
        require(!Files.isSymbolicLink(jtl), "Refusing a linked JTL file");
        Path result=env.directory.resolve("first-visible-"+System.currentTimeMillis()+".csv");
        Path metrics=env.directory.resolve("pipeline-"+System.currentTimeMillis()+".jsonl");
        Map<Long,Accepted> pending=new LinkedHashMap<>(); Set<Long> seen=new HashSet<>();
        Set<Long> ownedUsers=ids(manifest.path("users"),"id"), ownedCoupons=ids(manifest.path("vouchers"),"id");
        List<String> header=null; long offset=0,start=System.currentTimeMillis(),lastPoll=start,nextMetrics=start,maxGap=0,completed=0,duplicates=0;
        try (BufferedWriter csv=Files.newBufferedWriter(result,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
             BufferedWriter pipeline=Files.newBufferedWriter(metrics,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW)) {
            csv.write("orderId,userId,http_start_ms,http_accept_ms,jtl_discovered_ms,first_visible_ms,accept_to_visible_upper_ms,start_to_visible_upper_ms,discovery_delay_ms,poll_gap_ms,query_ms,accepted_before_observer\n"); csv.flush();
            while(System.currentTimeMillis()-start < duration*1000L) {
                long tick=System.currentTimeMillis(),gap=tick-lastPoll; maxGap=Math.max(maxGap,gap); lastPoll=tick;
                if(Files.exists(jtl)) {
                    try(RandomAccessFile file=new RandomAccessFile(jtl.toFile(),"r")) {
                        require(file.length() >= offset, "JTL truncated while observing"); file.seek(offset);
                        while(true) {
                            long lineStart=file.getFilePointer(); String line=file.readLine(); if(line==null) break;
                            // JMeter flushes complete newline-delimited rows; retain an incomplete trailing row.
                            long end=file.getFilePointer(); file.seek(end-1); int last=file.read(); file.seek(end);
                            if(last!='\n') { file.seek(lineStart); break; }
                            offset=end; List<String> fields=csvFields(line);
                            if(header==null) { header=fields; require(header.containsAll(Arrays.asList("timeStamp","elapsed","label","business_success","orderId","accepted_at","userId")), "Required safe JTL fields missing"); continue; }
                            require(fields.size()==header.size(), "JTL row/header column mismatch");
                            Map<String,String> row=new HashMap<>(); for(int i=0;i<header.size();i++) row.put(header.get(i),fields.get(i));
                            if(!"SECKILL_ACCEPT".equals(row.get("label")) || !"true".equals(row.get("business_success"))) continue;
                            String idText=row.get("orderId"); require(idText.matches("[1-9][0-9]{0,18}"), "Order ID must be full decimal string");
                            long id=Long.parseLong(idText),user=Long.parseLong(row.get("userId"));
                            require(ownedUsers.contains(user), "Accepted JTL user does not belong to this run");
                            if(!seen.add(id)) { duplicates++; continue; }
                            long accepted=Long.parseLong(row.get("accepted_at")),httpStart=Long.parseLong(row.get("timeStamp"));
                            require(accepted>=httpStart, "JTL acceptance timestamp invalid");
                            pending.put(id,new Accepted(id,httpStart,accepted,System.currentTimeMillis(),user));
                        }
                    }
                }
                List<Long> batchIds=new ArrayList<>(pending.keySet());
                for(int from=0;from<batchIds.size();from+=500) {
                    List<Long> chunk=batchIds.subList(from,Math.min(from+500,batchIds.size()));
                    long queryStart=System.currentTimeMillis(); List<long[]> found=new ArrayList<>();
                    try(PreparedStatement ps=env.statement("SELECT id,user_id,voucher_id FROM tb_voucher_order WHERE id IN ("+marks(chunk.size())+")",chunk.toArray());ResultSet rs=ps.executeQuery()) {
                        while(rs.next()) found.add(new long[]{rs.getLong(1),rs.getLong(2),rs.getLong(3)});
                    }
                    long visible=System.currentTimeMillis(),queryMs=visible-queryStart;
                    for(long[] order:found) {
                        Accepted item=pending.remove(order[0]); require(item!=null && item.user==order[1] && ownedCoupons.contains(order[2]), "Visible order identity mismatch");
                        csv.write(item.id+","+item.user+","+item.start+","+item.accepted+","+item.discovered+","+visible+","+Math.max(0,visible-item.accepted)+","+Math.max(0,visible-item.start)+","+Math.max(0,item.discovered-item.accepted)+","+gap+","+queryMs+","+(item.accepted<start)+"\n"); completed++;
                    }
                }
                csv.flush();
                if(tick>=nextMetrics) {
                    ObjectNode metric=JSON.createObjectNode(); metric.put("timeMs",System.currentTimeMillis()); metric.put("acceptedUnique",seen.size());
                    metric.put("firstVisibleCompleted",completed); metric.put("httpAcceptedNotVisible",pending.size()); metric.put("duplicateAcceptedOrderIds",duplicates);
                    metric.put("maxObservedPollGapMs",maxGap); metric.put("nominalPollMs",poll);
                    metric.put("completionTpsSinceObserverStart",completed/Math.max(0.001,(System.currentTimeMillis()-start)/1000.0));
                    try { metric.set("outbox",env.outbox()); } catch(Exception e) { metric.put("outbox","UNKNOWN"); }
                    try { metric.set("kafka",env.kafka()); } catch(Exception e) { metric.put("kafka","UNKNOWN"); }
                    metric.put("publisherErrors","UNKNOWN: collect scoped backend log counters separately");
                    pipeline.write(JSON.writeValueAsString(metric)+"\n"); pipeline.flush(); nextMetrics=tick+1000;
                }
                long sleep=poll-(System.currentTimeMillis()-tick); if(sleep>0) Thread.sleep(sleep);
            }
        }
        ObjectNode summary=JSON.createObjectNode(); summary.put("acceptedUnique",seen.size()); summary.put("firstVisibleCompleted",completed);
        summary.put("pendingNotVisible",pending.size()); summary.put("duplicateAcceptedOrderIds",duplicates); summary.put("maxPollGapMs",maxGap);
        summary.put("latencySemantics","UPPER_BOUND: includes JTL flush/discovery, observer startup, scheduling and SQL round-trip; not exact commit timestamp");
        ArrayNode missing=summary.putArray("missingOrderIds"); for(long id:pending.keySet()) missing.add(String.valueOf(id));
        try { summary.set("finalConsistency",env.consistency(manifest)); }
        catch (Exception e) { summary.put("finalConsistency","UNKNOWN: final inventory/Kafka query failed; preserve captured visibility evidence"); }
        JSON.writerWithDefaultPrettyPrinter().writeValue(env.directory.resolve("observer-summary-"+System.currentTimeMillis()+".json").toFile(),summary);
        System.out.println("OBSERVER_FINISHED accepted="+seen.size()+" visible="+completed+" pending="+pending.size()+"; exact commit latency UNKNOWN");
    }
    public static List<String> csvFields(String line) {
        List<String> fields=new ArrayList<>(); StringBuilder field=new StringBuilder(); boolean quoted=false;
        for(int i=0;i<line.length();i++) {
            char c=line.charAt(i);
            if(c=='"') { if(quoted && i+1<line.length() && line.charAt(i+1)=='"') { field.append('"'); i++; } else quoted=!quoted; }
            else if(c==',' && !quoted) { fields.add(field.toString()); field.setLength(0); } else field.append(c);
        }
        require(!quoted,"Malformed quoted JTL row"); fields.add(field.toString()); return fields;
    }
}
