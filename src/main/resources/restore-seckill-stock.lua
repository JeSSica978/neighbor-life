-- 仅接受 DB 已提交的取消/退款事件。永久 marker 让重复投递只返一次库存。
-- marker 与库存必须原子更新：若先返库再标记，进程崩溃后的重试会重复加库存。
local stockType = redis.call('TYPE', KEYS[1]).ok
local markerType = redis.call('TYPE', KEYS[2]).ok
if markerType == 'string' then return 0 end
if markerType ~= 'none' or stockType ~= 'string' then return 2 end
local stock = redis.call('GET', KEYS[1])
if not string.match(stock, '^%d+$') then return 2 end
local reservationType = redis.call('TYPE', KEYS[3]).ok
if reservationType ~= 'none' and reservationType ~= 'hash' then return 2 end
if reservationType == 'hash' then
    if redis.call('HGET', KEYS[3], 'userId') ~= ARGV[1] or
       redis.call('HGET', KEYS[3], 'voucherId') ~= ARGV[2] then return 2 end
end
redis.call('INCR', KEYS[1])
redis.call('SET', KEYS[2], '1')
if reservationType == 'hash' then redis.call('HSET', KEYS[3], 'state', ARGV[3] or 'CANCELLED') end
-- Keep the purchase set: one user / one voucher includes cancelled orders.
return 1
