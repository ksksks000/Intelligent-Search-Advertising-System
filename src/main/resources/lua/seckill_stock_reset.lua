-- 秒杀库存分段强制重置：删除库存相关 key 后按新容量重建
-- KEYS/ARGV 与 seckill_stock_init.lua 完全一致
-- 保留 KEYS[5]（order set），已下单用户仍然不能重复购买
for i = 1, #KEYS do
    if i ~= 5 then
        redis.call('del', KEYS[i])
    end
end

-- 旧版单 key 库存已在循环中删除

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
redis.call('pexpireat', KEYS[1], expireAt)
redis.call('pexpireat', KEYS[2], expireAt)
redis.call('pexpireat', KEYS[3], expireAt)
return 1
