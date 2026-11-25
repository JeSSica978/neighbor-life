-- Redis TIME 统一多实例的时钟；UUID member 保证同一毫秒的请求也各占一个名额。
-- KEYS[1]=接口和身份维度，ARGV=窗口秒数、阈值、唯一请求编号。
local window = tonumber(ARGV[1])
local limit = tonumber(ARGV[2])
if not window or window < 1 or not limit or limit < 1 or not ARGV[3] then
    return redis.error_reply('Invalid limiter parameters')
end
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - window * 1000)
local count = redis.call('ZCARD', KEYS[1])
if count >= limit then return 0 end
redis.call('ZADD', KEYS[1], now, ARGV[3])
redis.call('EXPIRE', KEYS[1], window)
return count + 1
