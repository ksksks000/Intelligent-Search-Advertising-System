-- 广告频控：用户/广告/日期 ZSet，相同 IP 对匿名流量兜底。
-- KEYS[1] userFreqKey, KEYS[2] ipFreqKey
-- ARGV[1] nowMillis, ARGV[2] dailyLimit, ARGV[3] minIntervalMs, ARGV[4] ipDailyLimit,
--      ARGV[5] memberId, ARGV[6] ttlSeconds
-- 返回 1 通过, 0 最小间隔未到, -1 用户日频控, -2 IP 日频控

local userKey = KEYS[1]
local ipKey = KEYS[2]
local now = tonumber(ARGV[1])
local dailyLimit = tonumber(ARGV[2])
local minInterval = tonumber(ARGV[3])
local ipLimit = tonumber(ARGV[4])
local member = ARGV[5]
local ttl = tonumber(ARGV[6])

local last = redis.call('ZREVRANGE', userKey, 0, 0, 'WITHSCORES')
if last and #last >= 2 then
    local lastTs = tonumber(last[2])
    if lastTs and now - lastTs < minInterval then
        return 0
    end
end

if dailyLimit > 0 and redis.call('ZCARD', userKey) >= dailyLimit then
    return -1
end
if ipLimit > 0 and redis.call('ZCARD', ipKey) >= ipLimit then
    return -2
end

redis.call('ZADD', userKey, now, member)
redis.call('EXPIRE', userKey, ttl)
if ipLimit > 0 then
    redis.call('ZADD', ipKey, now, member)
    redis.call('EXPIRE', ipKey, ttl)
end
return 1
