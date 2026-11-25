-- Run once with the backend stopped, after schema/data backup and preflight review.
-- Preflight must return no rows. Never discard historical orders to satisfy a constraint.
SELECT o.id, o.voucher_id FROM tb_voucher_order o
LEFT JOIN tb_voucher v ON v.id=o.voucher_id
WHERE v.id IS NULL OR v.type NOT IN (0,1);

-- NULL permits multiple ordinary orders; only seckill orders set seckill_voucher_id.
ALTER TABLE tb_voucher_order
  ADD COLUMN seckill_voucher_id bigint unsigned DEFAULT NULL,
  ADD COLUMN request_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  ADD UNIQUE KEY uk_voucher_order_seckill (user_id,seckill_voucher_id),
  ADD UNIQUE KEY uk_voucher_order_request (user_id,request_id),
  ADD INDEX idx_order_user_voucher (user_id,voucher_id);

-- Preserve the one-user/one-seckill-voucher rule, including cancelled historical orders.
UPDATE tb_voucher_order o JOIN tb_voucher v ON v.id=o.voucher_id
SET o.seckill_voucher_id=o.voucher_id WHERE v.type=1;

-- Drop the old constraint only after the backfill and new constraints have succeeded.
ALTER TABLE tb_voucher_order DROP INDEX uk_voucher_order_user_voucher;
