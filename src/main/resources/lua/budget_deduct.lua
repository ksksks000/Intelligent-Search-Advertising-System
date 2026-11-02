-- 广告预算/准入：频控 + 总预算 + 日预算 + 幂等，单次 Lua 原子完成。
-- KEYS[1] userFreqZSet, KEYS[2] ipFreqZSet, KEYS[3] totalBudget, KEYS[4] dailyBudget, KEYS[5] idem
-- ARGV: now, dailyLimit, minIntervalMs, ipLimit, member, freqTtl, cost,
--       totalBudget, dailyBudget, dailyTtl, idemTtl
-- 返回 {code, totalAfter, dailyAfter}
-- 1 成功; 2 幂等重复; 0 最小间隔; -1 用户日频控; -2 IP 日频控;
-- -3 总预算不足; -4 日预算不足; -5 未配置总预算

local userKey = KEYS[1]
local ipKey = KEYS[2]
local totalKey = KEYS[3]
local dailyKey = KEYS[4]
local idemKey = KEYS[5]
local now = tonumber(ARGV[1])
local dailyLimit = tonumber(ARGV[2])
local minInterval = tonumber(ARGV[3])
local ipLimit = tonumber(ARGV[4])
local member = ARGV[5]
local freqTtl = tonumber(ARGV[6])
local cost = tonumber(ARGV[7])
local totalBudget = tonumber(ARGV[8])
local dailyBudget = tonumber(ARGV[9])
local dailyTtl = tonumber(ARGV[10])
local idemTtl = tonumber(ARGV[11])
local mode = ARGV[12] or 'deduct'

if cost == nil or cost <= 0 then
    return {0, 0, 0}
end
if mode == 'refund' then
    if redis.call('EXISTS', idemKey) == 0 then
        return {4, 0, 0}
    end
    local totalAfter = redis.call('INCRBY', totalKey, cost)
    local dailyAfter = -1
    if redis.call('EXISTS', dailyKey) == 1 then
        dailyAfter = redis.call('INCRBY', dailyKey, cost)
    end
    redis.call('DEL', idemKey)
    return {3, totalAfter, dailyAfter}
end
if redis.call('EXISTS', idemKey) == 1 then
    return {2, 0, 0}
end

local useFrequency = dailyLimit > 0 or minInterval > 0 or ipLimit > 0
if useFrequency then
    local last = redis.call('ZREVRANGE', userKey, 0, 0, 'WITHSCORES')
    if last and #last >= 2 and now - tonumber(last[2]) < minInterval then
        return {0, 0, 0}
    end
    if dailyLimit > 0 and redis.call('ZCARD', userKey) >= dailyLimit then
        return {-1, 0, 0}
    end
    if ipLimit > 0 and redis.call('ZCARD', ipKey) >= ipLimit then
        return {-2, 0, 0}
    end
end

if redis.call('EXISTS', totalKey) == 0 then
    if totalBudget <= 0 then
        return {-5, 0, 0}
    end
    redis.call('SET', totalKey, totalBudget)
end
if dailyBudget > 0 and redis.call('EXISTS', dailyKey) == 0 then
    redis.call('SET', dailyKey, dailyBudget, 'EX', dailyTtl)
end

local totalAfter = redis.call('INCRBY', totalKey, -cost)
if totalAfter < 0 then
    redis.call('INCRBY', totalKey, cost)
    return {-3, 0, 0}
end
local dailyAfter = -1
if dailyBudget > 0 then
    dailyAfter = redis.call('INCRBY', dailyKey, -cost)
    if dailyAfter < 0 then
        redis.call('INCRBY', dailyKey, cost)
        redis.call('INCRBY', totalKey, cost)
        return {-4, 0, 0}
    end
end

if useFrequency then
    redis.call('ZADD', userKey, now, member)
    redis.call('EXPIRE', userKey, freqTtl)
    if ipLimit > 0 then
        redis.call('ZADD', ipKey, now, member)
        redis.call('EXPIRE', ipKey, freqTtl)
    end
end
redis.call('SET', idemKey, '1', 'EX', idemTtl)
return {1, totalAfter, dailyAfter}
