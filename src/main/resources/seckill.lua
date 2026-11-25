-- 1.参数列表
-- 1.1.优惠券id
local voucherId = ARGV[1]
-- 1.2.用户id
local userId = ARGV[2]
-- 1.3.订单id
local orderId = ARGV[3]

-- 2.数据key
-- 2.1.库存key
local stockKey = KEYS[1]
-- 2.2.订单key
local orderKey = KEYS[2]
local outboxKey = KEYS[3]
local reservationKey = KEYS[4]
local requestKey = KEYS[5]

-- Lua 会阻止其他客户端插入操作，但运行时错误不会回滚先前命令。
-- 因此必须先校验全部键类型及快照，再预扣库存，不能扣到一半才发现 outbox 类型错误。
local function keyType(key)
    return redis.call('type', key).ok
end
if keyType(stockKey) == 'none' then return 3 end
if keyType(stockKey) ~= 'string'
    or (keyType(orderKey) ~= 'none' and keyType(orderKey) ~= 'set')
    or (keyType(outboxKey) ~= 'none' and keyType(outboxKey) ~= 'stream')
    or keyType(reservationKey) ~= 'none'
    or (keyType(requestKey) ~= 'none' and keyType(requestKey) ~= 'hash') then return 4 end
if not ARGV[4] or not cjson.decode(ARGV[4]) then return 4 end
local stock = tonumber(redis.call('get', stockKey))
if not stock or stock % 1 ~= 0 then return 4 end
if redis.call('sismember', orderKey, userId) == 1 then return 2 end

-- 3.脚本业务
-- 3.1.判断库存是否充足 get stockKey
if(stock <= 0) then
    -- 3.2.库存不足，返回1
    return 1
end
-- 3.4.扣库存 incrby stockKey -1
redis.call('incrby', stockKey, -1)
-- 3.5.下单（保存用户）sadd orderKey userId
redis.call('sadd', orderKey, userId)
-- 预占、受理时商品快照、原订单号与 outbox 一起写入。
-- HTTP 返回的是“受理”，发布者稍后送到 Kafka，消费者再以 DB 事务建单。
-- 任一步重试都使用这个原订单号；取消后仍保留限购 SET，符合秒杀一人一券规则。
redis.call('hset', reservationKey, 'userId', userId, 'voucherId', voucherId, 'state', 'PENDING')
redis.call('hset', reservationKey, 'snapshot', ARGV[4])
redis.call('hset', requestKey, userId, orderId)
redis.call('xadd', outboxKey, '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)
return 0
