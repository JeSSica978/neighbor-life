-- Preflight: abort migration if this returns rows; never delete existing orders to add the index.
SELECT user_id, voucher_id, COUNT(*) AS duplicate_count
FROM tb_voucher_order GROUP BY user_id, voucher_id HAVING COUNT(*) > 1;

-- Run once after checking information_schema.statistics for this index.
ALTER TABLE tb_voucher_order ADD UNIQUE KEY uk_voucher_order_user_voucher (user_id, voucher_id);
