-- 秒杀库存分段幂等初始化：meta 已存在则直接返回 0
-- KEYS:
--   1 meta, 2 total, 3 cursor, 4 soldout, 5 order, 6 rollback, 7 legacyStock
--   8..(7+N) segment keys
-- ARGV:
--   1 remainingStock（写入 total key，剩余库存）
--   2 metaTotal（初始总量 = 剩余库存 + 已有订单数，写入 meta.total）
--   3 segments, 4 begin, 5 end, 6 expireAt
--   7..(6+N) 每个段的容量
--
-- 注意：不删除 KEYS[5]（order set），保证重复初始化/重启不会丢失一人一单记录。
-- 无论是否已初始化，都清理旧版单 key 库存（seckill:stock:{voucherId}）
redis.call('del', KEYS[7])

if redis.call('exists', KEYS[1]) == 1 then
    return 0
end

local remainingStock = tonumber(ARGV[1])
local metaTotal = tonumber(ARGV[2])
local segments = tonumber(ARGV[3])
local beginTime = tonumber(ARGV[4])
local endTime = tonumber(ARGV[5])
local expireAt = tonumber(ARGV[6])

redis.call('hset', KEYS[1],
        'begin', beginTime,
        'end', endTime,
        'segments', segments,
        'total', metaTotal)

for i = 1, segments do
    local cap = tonumber(ARGV[6 + i])
    redis.call('hset', KEYS[1], 'cap:' .. i, cap)
    redis.call('set', KEYS[7 + i], cap)
    redis.call('pexpireat', KEYS[7 + i], expireAt)
end

redis.call('set', KEYS[2], remainingStock)
redis.call('set', KEYS[3], 0)
redis.call('del', KEYS[4])
redis.call('del', KEYS[6])
redis.call('del', KEYS[7])
redis.call('pexpireat', KEYS[1], expireAt)
redis.call('pexpireat', KEYS[2], expireAt)
redis.call('pexpireat', KEYS[3], expireAt)
return 1
