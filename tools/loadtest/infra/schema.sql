-- Schema only, derived from src/main/resources/db/hmdp.sql. No DROP, no business seed data.
-- Preserve development zero-date defaults; 10-init.sh relaxes only the schema import session.
-- AUTO_INCREMENT starts at 1000000; original data IDs are never reused.
-- Source SHA256: 5812d7f9eb97fe27990a8741b1d06dec4404844c40180e945a35b60a3068c406
USE hmdp_loadtest;

CREATE TABLE `tb_blog`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT ,
  `shop_id` bigint(20) NOT NULL ,
  `user_id` bigint(20) UNSIGNED NOT NULL ,
  `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL ,
  `images` varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL ,
  `content` varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL ,
  `liked` int(8) UNSIGNED NULL DEFAULT 00000000 ,
  `comments` int(8) UNSIGNED NULL DEFAULT NULL ,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP ,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1000000 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

CREATE TABLE `tb_blog_comments`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT ,
  `user_id` bigint(20) UNSIGNED NOT NULL ,
  `blog_id` bigint(20) UNSIGNED NOT NULL ,
  `parent_id` bigint(20) UNSIGNED NOT NULL ,
  `answer_id` bigint(20) UNSIGNED NOT NULL ,
  `content` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL ,
  `liked` int(8) UNSIGNED NULL DEFAULT NULL ,
  `status` tinyint(1) UNSIGNED NULL DEFAULT NULL ,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP ,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1000000 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

CREATE TABLE `tb_follow`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT ,
  `user_id` bigint(20) UNSIGNED NOT NULL ,
  `follow_user_id` bigint(20) UNSIGNED NOT NULL ,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

CREATE TABLE `tb_seckill_voucher`  (
  `voucher_id` bigint(20) UNSIGNED NOT NULL ,
  `stock` int(8) NOT NULL ,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ,
  `begin_time` timestamp NOT NULL DEFAULT '0000-00-00 00:00:00' ,
  `end_time` timestamp NOT NULL DEFAULT '0000-00-00 00:00:00' ,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP ,
  PRIMARY KEY (`voucher_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci  ROW_FORMAT = Compact;

CREATE TABLE `tb_shop`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT ,
  `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL ,
  `type_id` bigint(20) UNSIGNED NOT NULL ,
  `images` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL ,
  `area` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL ,
  `address` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL ,
  `x` double UNSIGNED NOT NULL ,
  `y` double UNSIGNED NOT NULL ,
  `avg_price` bigint(10) UNSIGNED NULL DEFAULT NULL ,
  `sold` int(10) UNSIGNED NOT NULL ,
  `comments` int(10) UNSIGNED NOT NULL ,
  `score` int(2) UNSIGNED NOT NULL ,
  `open_hours` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL ,
  `create_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP ,
  `update_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP ,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `foreign_key_type`(`type_id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1000000 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

CREATE TABLE `tb_shop_type`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT ,
  `name` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL ,
  `icon` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL ,
  `sort` int(3) UNSIGNED NULL DEFAULT NULL ,
  `create_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP ,
  `update_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP ,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1000000 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

CREATE TABLE `tb_user`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT ,
  `phone` varchar(11) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL ,
  `password` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT '' ,
  `nick_name` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT '' ,
  `icon` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT '' ,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP ,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uniqe_key_phone`(`phone`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1000000 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

CREATE TABLE `tb_user_info`  (
  `user_id` bigint(20) UNSIGNED NOT NULL ,
  `city` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT '' ,
  `introduce` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL ,
  `fans` int(8) UNSIGNED NULL DEFAULT 0 ,
  `followee` int(8) UNSIGNED NULL DEFAULT 0 ,
  `gender` tinyint(1) UNSIGNED NULL DEFAULT 0 ,
  `birthday` date NULL DEFAULT NULL ,
  `credits` int(8) UNSIGNED NULL DEFAULT 0 ,
  `level` tinyint(1) UNSIGNED NULL DEFAULT 0 ,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP ,
  PRIMARY KEY (`user_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

CREATE TABLE `tb_voucher`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT ,
  `shop_id` bigint(20) UNSIGNED NULL DEFAULT NULL ,
  `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL ,
  `sub_title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL ,
  `rules` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL ,
  `pay_value` bigint(10) UNSIGNED NOT NULL ,
  `actual_value` bigint(10) NOT NULL ,
  `type` tinyint(1) UNSIGNED NOT NULL DEFAULT 0 ,
  `status` tinyint(1) UNSIGNED NOT NULL DEFAULT 1 ,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP ,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1000000 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

CREATE TABLE `tb_voucher_order`  (
  `id` bigint(20) NOT NULL ,
  `user_id` bigint(20) UNSIGNED NOT NULL ,
  `voucher_id` bigint(20) UNSIGNED NOT NULL ,
  `pay_type` tinyint(1) UNSIGNED NOT NULL DEFAULT 1 ,
  `status` tinyint(1) UNSIGNED NOT NULL DEFAULT 1 ,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ,
  `pay_time` timestamp NULL DEFAULT NULL ,
  `use_time` timestamp NULL DEFAULT NULL ,
  `refund_time` timestamp NULL DEFAULT NULL ,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP ,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY uk_voucher_order_user_voucher (user_id,voucher_id)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;
CREATE TABLE lt_environment (id INT PRIMARY KEY, marker VARCHAR(64) NOT NULL);
INSERT INTO lt_environment(id,marker) VALUES (1,'neighborlife-loadtest-v1');
