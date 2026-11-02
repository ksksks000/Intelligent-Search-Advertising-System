-- 回滚一次 Redis 预扣（DB 落单永久失败时使用）
-- KEYS:
--   1 rollback set（requestId 防重）
--   2 order set
--   3 total
--   4 soldout
--   5 meta
--   6 目标 segment
-- ARGV:
--   1 requestId, 2 userId, 3 segment, 4 now, 5 buffer, 6 removeUser(1/0)
--
-- 返回：1 成功回滚；0 requestId 已回滚过（幂等）；-1 meta 或段容量缺失

local cap = tonumber(redis.call('hget', KEYS[5], 'cap:' .. ARGV[3]) or '-1')
if cap < 0 then
    return -1
end

-- requestId 已存在说明已经回滚过，直接返回，防止重复加库存
if redis.call('sadd', KEYS[1], ARGV[1]) == 0 then
    return 0
end
redis.call('pexpireat', KEYS[1], tonumber(ARGV[4]) + tonumber(ARGV[5]))

local cur = tonumber(redis.call('get', KEYS[6]) or '0')
if cur < cap then
    redis.call('incr', KEYS[6])
end
redis.call('incr', KEYS[3])
if ARGV[6] == '1' then
    redis.call('srem', KEYS[2], ARGV[2])
end

local total = tonumber(redis.call('get', KEYS[3]) or '0')
if total > 0 then
    redis.call('del', KEYS[4])
end
return 1
