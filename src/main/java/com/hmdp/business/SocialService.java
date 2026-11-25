package com.hmdp.business;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.ReliableEventService;
import java.util.*;
import javax.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SocialService {
  @Resource private BusinessStore db;
  @Resource private BusinessSchema schema;
  @Resource private StringRedisTemplate redis;
  @Resource private BlogMapper blogs;
  @Resource private CatalogService catalog;
  @Resource private ReliableEventService events;
  @Resource private AccessService access;
  @Resource private PlatformTransactionManager transactions;
  private static final DefaultRedisScript<Long> REPLACE =
      new DefaultRedisScript<>(
          "local data=cjson.decode(ARGV[2]);local t=redis.call('TYPE',KEYS[1]).ok;if t~='none' and t~=string.lower(ARGV[1]) then return 0 end;redis.call('DEL',KEYS[1]);for _,v in ipairs(data) do if ARGV[1]=='SET' then redis.call('SADD',KEYS[1],v.id) else redis.call('ZADD',KEYS[1],v.score,v.id) end end;redis.call('EXPIRE',KEYS[1],604800);return 1",
          Long.class);

  private void visible(long id) {
    if (db.count(
            "SELECT COUNT(*) FROM tb_blog b LEFT JOIN tb_blog_visibility v ON v.blog_id=b.id WHERE b.id=? AND COALESCE(v.status,1)=1",
            id)
        == 0) throw new BusinessException(404, "BLOG_NOT_FOUND", "笔记不存在或已删除");
  }

  private String select(long viewer) {
    return "SELECT b.*,u.nick_name name,u.icon,EXISTS(SELECT 1 FROM tb_blog_like l WHERE l.blog_id=b.id AND l.user_id="
        + viewer
        + ") is_like FROM tb_blog b JOIN tb_user u ON u.id=b.user_id LEFT JOIN tb_blog_visibility v ON v.blog_id=b.id";
  }

  public Result hot(int page, long viewer) {
    schema.require();
    BusinessStore.page(page, 10);
    return Result.ok(
        BusinessStore.views(
            db.jdbc.queryForList(
                select(viewer)
                    + " WHERE COALESCE(v.status,1)=1 ORDER BY b.liked DESC,b.id DESC LIMIT 10 OFFSET ?",
                (page - 1) * 10)));
  }

  public Map<String, Object> blog(long id, long viewer) {
    schema.require();
    visible(id);
    return BusinessStore.view(db.one(select(viewer) + " WHERE b.id=?", id));
  }

  public Result byUser(long user, int page) {
    schema.require();
    BusinessStore.page(page, 10);
    long viewer =
        com.hmdp.utils.UserHolder.getUser() == null
            ? 0
            : com.hmdp.utils.UserHolder.getUser().getId();
    return Result.ok(
        BusinessStore.views(
            db.jdbc.queryForList(
                select(viewer)
                    + " WHERE b.user_id=? AND COALESCE(v.status,1)=1 ORDER BY b.create_time DESC,b.id DESC LIMIT 10 OFFSET ?",
                user,
                (page - 1) * 10)));
  }

  @Transactional
  public void like(long id, long user, boolean liked) {
    schema.require();
    visible(id);
    db.one("SELECT id FROM tb_blog WHERE id=? FOR UPDATE", id);
    // 关系 INSERT/DELETE 真正改变时才增减计数；保留无法还原具体用户的历史汇总数。
    int delta =
        liked
            ? db.jdbc.update(
                "INSERT IGNORE INTO tb_blog_like(blog_id,user_id) VALUES(?,?)", id, user)
            : -db.jdbc.update("DELETE FROM tb_blog_like WHERE blog_id=? AND user_id=?", id, user);
    db.jdbc.update("UPDATE tb_blog SET liked=GREATEST(liked+?,0) WHERE id=?", delta, id);
    String event = events.create("BLOG_SYNC", id);
    events.afterCommit(event, "BLOG_SYNC");
  }

  public boolean liked(long id, long user) {
    return db.count("SELECT COUNT(*) FROM tb_blog_like WHERE blog_id=? AND user_id=?", id, user)
        > 0;
  }

  public List<Map<String, Object>> likes(long id) {
    schema.require();
    visible(id);
    if (!Boolean.TRUE.equals(redis.hasKey("blog:liked:" + id))) sync("BLOG_SYNC", id);
    Set<String> ids = redis.opsForZSet().range("blog:liked:" + id, 0, 4);
    List<Map<String, Object>> out = new ArrayList<>();
    if (ids != null)
      for (String user : ids) {
        Map<String, Object> u =
            db.one("SELECT id,nick_name,icon FROM tb_user WHERE id=?", Long.parseLong(user));
        if (u != null) out.add(BusinessStore.view(u));
      }
    return out;
  }

  @Transactional
  public long post(Blog b, long user) {
    schema.require();
    if (b.getTitle() == null
        || b.getTitle().trim().isEmpty()
        || b.getTitle().length() > 255
        || b.getContent() == null
        || b.getContent().trim().isEmpty()
        || b.getContent().length() > 10000)
      throw new BusinessException(400, "INVALID_BLOG", "笔记标题或正文不正确");
    if (b.getShopId() == null
        || db.count("SELECT COUNT(*) FROM tb_shop WHERE id=?", b.getShopId()) == 0)
      throw new BusinessException(404, "SHOP_NOT_FOUND", "关联店铺不存在");
    catalog.images(b.getImages(), user, "");
    b.setId(null);
    b.setUserId(user);
    b.setLiked(0);
    b.setComments(0);
    b.setCreateTime(null);
    b.setUpdateTime(null);
    if (b.getImages() == null) b.setImages("");
    blogs.insert(b);
    db.jdbc.update("INSERT INTO tb_blog_visibility(blog_id,status) VALUES(?,1)", b.getId());
    String event = events.create("BLOG_FEED", b.getId());
    events.afterCommit(event, "BLOG_FEED");
    return b.getId();
  }

  @Transactional
  public void edit(long id, long user, Map<String, Object> b) {
    schema.require();
    Map<String, Object> old = db.one("SELECT * FROM tb_blog WHERE id=? FOR UPDATE", id);
    if (old == null || BusinessStore.number(old, "user_id") != user)
      throw new BusinessException(403, "BLOG_FORBIDDEN", "只能修改自己的笔记");
    visible(id);
    String title = BusinessStore.required(b, "title", 255),
        content = BusinessStore.required(b, "content", 10000),
        images = BusinessStore.string(b, "images");
    catalog.images(images, user, BusinessStore.string(old, "images"));
    db.jdbc.update(
        "UPDATE tb_blog SET title=?,content=?,images=? WHERE id=?", title, content, images, id);
  }

  @Transactional
  public void delete(long id, long user) {
    schema.require();
    Map<String, Object> b = db.one("SELECT * FROM tb_blog WHERE id=? FOR UPDATE", id);
    if (b == null || BusinessStore.number(b, "user_id") != user && !access.admin(user))
      throw new BusinessException(403, "BLOG_FORBIDDEN", "只能删除自己的笔记");
    db.jdbc.update(
        "INSERT INTO tb_blog_visibility(blog_id,status) VALUES(?,2) ON DUPLICATE KEY UPDATE status=2",
        id);
    String event = events.create("BLOG_FEED", id);
    events.afterCommit(event, "BLOG_FEED");
  }

  @Transactional
  public void follow(long user, long target, boolean yes) {
    schema.require();
    if (user == target || db.count("SELECT COUNT(*) FROM tb_user WHERE id=?", target) == 0)
      throw new BusinessException(400, "INVALID_FOLLOW", "不能关注自己或不存在的用户");
    db.one("SELECT id FROM tb_user WHERE id=? FOR UPDATE", user);
    if (yes)
      db.jdbc.update(
          "INSERT IGNORE INTO tb_social_follow(user_id,follow_user_id) VALUES(?,?)", user, target);
    else
      db.jdbc.update(
          "DELETE FROM tb_social_follow WHERE user_id=? AND follow_user_id=?", user, target);
    String event = events.create("FOLLOW_SYNC", user);
    events.afterCommit(event, "FOLLOW_SYNC");
  }

  public boolean follows(long user, long target) {
    schema.require();
    return db.count(
            "SELECT COUNT(*) FROM tb_social_follow WHERE user_id=? AND follow_user_id=?",
            user,
            target)
        > 0;
  }

  public List<Map<String, Object>> common(long user, long target) {
    schema.require();
    if (!Boolean.TRUE.equals(redis.hasKey("follows:" + user))) sync("FOLLOW_SYNC", user);
    if (!Boolean.TRUE.equals(redis.hasKey("follows:" + target))) sync("FOLLOW_SYNC", target);
    Set<String> ids = redis.opsForSet().intersect("follows:" + user, "follows:" + target);
    List<Map<String, Object>> out = new ArrayList<>();
    if (ids != null)
      for (String id : ids) {
        Map<String, Object> u =
            db.one("SELECT id,nick_name,icon FROM tb_user WHERE id=?", Long.parseLong(id));
        if (u != null) out.add(BusinessStore.view(u));
      }
    return out;
  }

  private void replace(String key, String type, List<Map<String, Object>> rows) {
    Long ok =
        redis.execute(
            REPLACE, Collections.singletonList(key), type, cn.hutool.json.JSONUtil.toJsonStr(rows));
    if (ok == null || ok != 1) throw new IllegalStateException("Social index type invariant");
  }

  public void sync(String type, long resource) {
    // MySQL 保存真实关系，Redis SET/ZSET 是可重建的查询索引。事件重投时重读最新数据，
    // 用 Lua 一次替换整个索引，避免查询者看见 DEL 与逐条添加之间的半成品。
    // 拿到索引所属用户/笔记锁后再读最新关系，避免事件处理中沿用旧的可重复读快照。
    TransactionTemplate tx = new TransactionTemplate(transactions);
    tx.setPropagationBehavior(
        org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    tx.setIsolationLevel(
        org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    tx.execute(
        s -> {
          if ("BLOG_SYNC".equals(type)) {
            db.one("SELECT id FROM tb_blog WHERE id=? FOR UPDATE", resource);
            replace(
                "blog:liked:" + resource,
                "ZSET",
                db.jdbc.queryForList(
                    "SELECT CAST(user_id AS CHAR) id,UNIX_TIMESTAMP(create_time)*1000 score FROM tb_blog_like WHERE blog_id=?",
                    resource));
          } else if ("FOLLOW_SYNC".equals(type)) {
            db.one("SELECT id FROM tb_user WHERE id=? FOR UPDATE", resource);
            replace(
                "follows:" + resource,
                "SET",
                db.jdbc.queryForList(
                    "SELECT CAST(follow_user_id AS CHAR) id FROM tb_social_follow WHERE user_id=?",
                    resource));
            replace(
                "feed:" + resource,
                "ZSET",
                db.jdbc.queryForList(
                    "SELECT CAST(b.id AS CHAR) id,UNIX_TIMESTAMP(b.create_time)*1000 score FROM tb_blog b JOIN tb_social_follow f ON f.follow_user_id=b.user_id LEFT JOIN tb_blog_visibility v ON v.blog_id=b.id WHERE f.user_id=? AND COALESCE(v.status,1)=1 ORDER BY b.create_time DESC,b.id DESC LIMIT 1000",
                    resource));
          } else if ("BLOG_FEED".equals(type)) {
            Map<String, Object> b = db.one("SELECT user_id FROM tb_blog WHERE id=?", resource);
            if (b == null) throw new IllegalStateException("Missing published blog");
            for (Long user :
                db.jdbc.queryForList(
                    "SELECT user_id FROM tb_social_follow WHERE follow_user_id=?",
                    Long.class,
                    b.get("user_id"))) sync("FOLLOW_SYNC", user);
          } else throw new IllegalArgumentException("Unknown social event");
          return null;
        });
  }
  // 游标=最后一条 score + 该 score 已读数量，避免多条笔记同毫秒时漏读或重复。
  public Result feed(long user, long max, int offset) {
    schema.require();
    if (max < 0 || offset < 0 || offset > 10000)
      throw new BusinessException(400, "INVALID_CURSOR", "动态游标不正确");
    if (!Boolean.TRUE.equals(redis.hasKey("feed:" + user))) sync("FOLLOW_SYNC", user);
    Set<ZSetOperations.TypedTuple<String>> tuples =
        redis.opsForZSet().reverseRangeByScoreWithScores("feed:" + user, 0, max, offset, 10);
    List<Map<String, Object>> rows = new ArrayList<>();
    long min = max;
    int next = 0;
    if (tuples != null)
      for (ZSetOperations.TypedTuple<String> t : tuples) {
        long time = t.getScore().longValue();
        if (time == min) next++;
        else {
          min = time;
          next = 1;
        }
        long id = Long.parseLong(t.getValue());
        if (db.count("SELECT COUNT(*) FROM tb_blog_visibility WHERE blog_id=? AND status=2", id)
            == 0) rows.add(blog(id, user));
      }
    if (min == max) next += offset;
    // 若整页都停在原 score，必须累计上一页 offset；换了 score 后只计新 score 的条数。
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("list", rows);
    out.put("minTime", min);
    out.put("offset", next);
    return Result.ok(out);
  }

  @Transactional
  public void favorite(long user, long shop, boolean yes) {
    schema.require();
    if (db.count("SELECT COUNT(*) FROM tb_shop WHERE id=?", shop) == 0)
      throw new BusinessException(404, "SHOP_NOT_FOUND", "店铺不存在");
    if (yes)
      db.jdbc.update("INSERT IGNORE INTO tb_favorite(user_id,shop_id) VALUES(?,?)", user, shop);
    else db.jdbc.update("DELETE FROM tb_favorite WHERE user_id=? AND shop_id=?", user, shop);
  }

  public Result favorites(long user, int page, int size) {
    schema.require();
    BusinessStore.page(page, size);
    return Result.ok(
        BusinessStore.views(
            db.jdbc.queryForList(
                "SELECT s.*,1 is_favorite FROM tb_shop s JOIN tb_favorite f ON f.shop_id=s.id LEFT JOIN tb_shop_settings st ON st.shop_id=s.id WHERE f.user_id=? AND COALESCE(st.status,1)=1 ORDER BY f.create_time DESC,s.id DESC LIMIT ? OFFSET ?",
                user,
                size,
                (page - 1) * size)),
        db.count(
            "SELECT COUNT(*) FROM tb_favorite f JOIN tb_shop s ON s.id=f.shop_id LEFT JOIN tb_shop_settings st ON st.shop_id=s.id WHERE f.user_id=? AND COALESCE(st.status,1)=1",
            user));
  }

  public Result comments(long blog, int page) {
    schema.require();
    visible(blog);
    BusinessStore.page(page, 10);
    return Result.ok(
        BusinessStore.views(
            db.jdbc.queryForList(
                "SELECT c.*,u.nick_name name,u.icon FROM tb_blog_comments c JOIN tb_user u ON u.id=c.user_id WHERE c.blog_id=? AND c.status=0 ORDER BY c.create_time DESC,c.id DESC LIMIT 10 OFFSET ?",
                blog,
                (page - 1) * 10)),
        db.count("SELECT COUNT(*) FROM tb_blog_comments WHERE blog_id=? AND status=0", blog));
  }

  @Transactional
  public long comment(long blog, long user, Map<String, Object> b) {
    schema.require();
    visible(blog);
    db.one("SELECT id FROM tb_blog WHERE id=? FOR UPDATE", blog);
    long parent = BusinessStore.number(b, "parentId");
    if (parent < 0
        || parent > 0
            && db.count(
                    "SELECT COUNT(*) FROM tb_blog_comments WHERE id=? AND blog_id=? AND status=0",
                    parent,
                    blog)
                == 0) throw new BusinessException(400, "INVALID_PARENT", "回复的评论不存在");
    String content = BusinessStore.required(b, "content", 255);
    db.jdbc.update(
        "INSERT INTO tb_blog_comments(user_id,blog_id,parent_id,answer_id,content,status) VALUES(?,?,?,?,?,0)",
        user,
        blog,
        parent,
        parent,
        content);
    long id = db.count("SELECT LAST_INSERT_ID()");
    db.jdbc.update(
        "UPDATE tb_blog SET comments=(SELECT COUNT(*) FROM tb_blog_comments WHERE blog_id=? AND status=0) WHERE id=?",
        blog,
        blog);
    return id;
  }

  @Transactional
  public void deleteComment(long id, long user) {
    Map<String, Object> c = db.one("SELECT * FROM tb_blog_comments WHERE id=?", id);
    if (c == null || BusinessStore.number(c, "user_id") != user && !access.admin(user))
      throw new BusinessException(403, "COMMENT_FORBIDDEN", "只能删除自己的评论");
    db.one("SELECT id FROM tb_blog WHERE id=? FOR UPDATE", c.get("blog_id"));
    db.jdbc.update("UPDATE tb_blog_comments SET status=2 WHERE id=?", id);
    db.jdbc.update(
        "UPDATE tb_blog SET comments=(SELECT COUNT(*) FROM tb_blog_comments WHERE blog_id=? AND status=0) WHERE id=?",
        c.get("blog_id"),
        c.get("blog_id"));
  }

  public Map<String, Object> person(long user, long viewer) {
    schema.require();
    Map<String, Object> u = db.one("SELECT id,nick_name,icon FROM tb_user WHERE id=?", user);
    if (u == null) throw new BusinessException(404, "USER_NOT_FOUND", "用户不存在");
    Map<String, Object> v = BusinessStore.view(u);
    v.put("fans", db.count("SELECT COUNT(*) FROM tb_social_follow WHERE follow_user_id=?", user));
    v.put("followees", db.count("SELECT COUNT(*) FROM tb_social_follow WHERE user_id=?", user));
    v.put("following", viewer > 0 && follows(viewer, user));
    return v;
  }

  @Transactional
  public void review(long order, long user, Map<String, Object> b) {
    schema.require();
    Map<String, Object> o =
        db.one(
            "SELECT o.*,d.shop_id FROM tb_voucher_order o JOIN tb_order_detail d ON d.order_id=o.id WHERE o.id=? FOR UPDATE",
            order);
    if (o == null
        || BusinessStore.number(o, "user_id") != user
        || BusinessStore.number(o, "status") != 3)
      throw new BusinessException(409, "REVIEW_NOT_ALLOWED", "核销后才能评价");
    long score = BusinessStore.number(b, "score");
    if (score < 1 || score > 5) throw new BusinessException(400, "INVALID_SCORE", "评分须为 1–5");
    if (db.count("SELECT COUNT(*) FROM tb_shop_review WHERE order_id=?", order) > 0)
      throw new BusinessException(409, "ALREADY_REVIEWED", "该订单已评价");
    db.jdbc.update(
        "INSERT INTO tb_shop_review(order_id,shop_id,user_id,score,content) VALUES(?,?,?,?,?)",
        order,
        o.get("shop_id"),
        user,
        score,
        BusinessStore.required(b, "content", 1000));
    db.jdbc.update(
        "UPDATE tb_shop SET score=(SELECT ROUND(AVG(score)*10) FROM tb_shop_review WHERE shop_id=?),comments=(SELECT COUNT(*) FROM tb_shop_review WHERE shop_id=?) WHERE id=?",
        o.get("shop_id"),
        o.get("shop_id"),
        o.get("shop_id"));
    String event = events.create("CATALOG_SYNC", BusinessStore.number(o, "shop_id"));
    events.afterCommit(event, "CATALOG_SYNC");
  }
}
