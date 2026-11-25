-- Explicit migration; never automatically run against the development database.
-- Check SHOW INDEX first; run each statement once after backing up the schema.
ALTER TABLE tb_voucher_order ADD INDEX idx_order_status_create (status, create_time);

CREATE TABLE tb_reliable_event (
  id char(36) NOT NULL,
  event_type varchar(32) NOT NULL,
  resource_id bigint unsigned NOT NULL,
  status tinyint unsigned NOT NULL DEFAULT 0 COMMENT '0 pending, 1 sent, 2 completed, 3 blocked',
  send_attempts int unsigned NOT NULL DEFAULT 0,
  consume_attempts int unsigned NOT NULL DEFAULT 0,
  next_attempt_at datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_error varchar(128) DEFAULT NULL COMMENT 'Exception class only; never credentials or payloads',
  create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_event_dispatch (status, next_attempt_at),
  KEY idx_event_resource (event_type, resource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
