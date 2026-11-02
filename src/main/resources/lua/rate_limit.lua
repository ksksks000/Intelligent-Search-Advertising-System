-- 秒杀入口令牌桶限流（user / ip / api 三维度，单脚本原子判定）
-- KEYS[1] = user key, KEYS[2] = ip key, KEYS[3] = api key
-- ARGV[1] = now(ms), ARGV[2] = requested, ARGV[3] = ttl(ms)
-- ARGV[4] = user capacity, ARGV[5] = user refill rate(per second)
-- ARGV[6] = ip capacity,   ARGV[7] = ip refill rate
-- ARGV[8] = api capacity,  ARGV[9] = api refill rate
--
-- 返回 {allowed, remaining, dimension}
--   allowed: 1 通过 / 0 拒绝
--   dimension: 1 user / 2 ip / 3 api / 0 none
--   capacity <= 0 表示该维度关闭

local now = tonumber(ARGV[1])
local requested = tonumber(ARGV[2])
local ttl = tonumber(ARGV[3])

local function take(key, capacity, refillRate)
    if capacity <= 0 then
        return 1, 0
    end
    local data = redis.call('hmget', key, 'tokens', 'last')
    local tokens = tonumber(data[1])
    local last = tonumber(data[2])
    if tokens == nil then
        tokens = capacity
        last = now
    end
    local delta = now - last
    if delta < 0 then
        delta = 0
    end
    tokens = math.min(capacity, tokens + delta * refillRate / 1000)
    if tokens < requested then
        redis.call('hmset', key, 'tokens', tokens, 'last', now)
        redis.call('pexpire', key, ttl)
        return 0, math.floor(tokens)
    end
    tokens = tokens - requested
    redis.call('hmset', key, 'tokens', tokens, 'last', now)
    redis.call('pexpire', key, ttl)
    return 1, math.floor(tokens)
end

local userAllowed, userRemaining = take(KEYS[1], tonumber(ARGV[4]), tonumber(ARGV[5]))
if userAllowed == 0 then
    return {0, userRemaining, 1}
end

local ipAllowed, ipRemaining = take(KEYS[2], tonumber(ARGV[6]), tonumber(ARGV[7]))
if ipAllowed == 0 then
    return {0, ipRemaining, 2}
end

local apiAllowed, apiRemaining = take(KEYS[3], tonumber(ARGV[8]), tonumber(ARGV[9]))
if apiAllowed == 0 then
    return {0, apiRemaining, 3}
end

return {1, userRemaining, 0}
