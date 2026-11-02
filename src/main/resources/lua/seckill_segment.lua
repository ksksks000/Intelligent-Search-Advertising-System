-- 秒杀资格判定 + 段内库存原子扣减 + （可选）Stream 消息原子入队
-- KEYS[1] = seckill:meta:{voucherId}
-- ARGV[1] = voucherId
-- ARGV[2] = userId
-- ARGV[3] = orderId（即 requestId）
-- ARGV[4] = now（epoch millis，由调用方传入，避免脚本内 TIME 的非确定性）
-- ARGV[5] = metaTtlBufferMillis
-- ARGV[6] = enqueue（1=Stream 模式下在脚本内 XADD；0=Rabbit 模式由应用发送）
-- ARGV[7] = streamKey
-- ARGV[8] = streamMaxLen
--
-- 返回 {code, segment}
--  0 成功
--  1 总库存售罄
--  2 重复下单
--  3 秒杀未开始
--  4 秒杀已结束
--  5 售罄标记命中
-- -1 未初始化 / 元数据缺失 / Stream key 类型异常

local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]
local now = tonumber(ARGV[4])
local buffer = tonumber(ARGV[5])
local enqueue = ARGV[6]
local streamKey = ARGV[7]
local maxLen = tonumber(ARGV[8])

local metaKey = KEYS[1]
local totalKey = 'seckill:stock:' .. voucherId .. ':total'
local cursorKey = 'seckill:seg:cursor:' .. voucherId
local soldoutKey = 'seckill:soldout:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId

if redis.call('exists', metaKey) == 0 then
    return {-1, 0}
end

-- Stream 模式下先校验 key 类型，避免扣减后 XADD 才失败
if enqueue == '1' then
    local keyType = redis.call('type', streamKey)
    local typeName = keyType.ok or keyType
    if typeName ~= 'none' and typeName ~= 'stream' then
        return {-1, 0}
    end
end

local beginTime = tonumber(redis.call('hget', metaKey, 'begin') or '0')
local endTime = tonumber(redis.call('hget', metaKey, 'end') or '0')
local segments = tonumber(redis.call('hget', metaKey, 'segments') or '0')
if segments <= 0 then
    return {-1, 0}
end

-- 售罄标记/游标至少保留到活动结束后 buffer，避免提前失效
local expireAt = now + buffer
if endTime > 0 and endTime + buffer > expireAt then
    expireAt = endTime + buffer
end

if beginTime > 0 and now < beginTime then
    return {3, 0}
end
if endTime > 0 and now > endTime then
    return {4, 0}
end
if redis.call('exists', soldoutKey) == 1 then
    return {5, 0}
end
if redis.call('sismember', orderKey, userId) == 1 then
    return {2, 0}
end

local total = tonumber(redis.call('get', totalKey) or '-1')
if total < 0 then
    return {-1, 0}
end
if total <= 0 then
    redis.call('set', soldoutKey, '1')
    redis.call('pexpireat', soldoutKey, expireAt)
    return {1, 0}
end

-- 从游标处轮转，找第一个仍有库存的段，缓解段间不均
local cursor = tonumber(redis.call('get', cursorKey) or '0')
for offset = 1, segments do
    local idx = ((cursor + offset - 1) % segments) + 1
    local segKey = 'seckill:stock:' .. voucherId .. ':s:' .. idx
    local segStock = tonumber(redis.call('get', segKey) or '0')
    if segStock > 0 then
        redis.call('decr', segKey)
        redis.call('decr', totalKey)
        redis.call('sadd', orderKey, userId)
        redis.call('set', cursorKey, idx)
        redis.call('pexpireat', cursorKey, expireAt)
        if enqueue == '1' then
            if maxLen <= 0 then
                maxLen = 1000000
            end
            redis.call('xadd', streamKey, 'MAXLEN', '~', maxLen, '*',
                    'requestId', orderId,
                    'userId', userId,
                    'voucherId', voucherId,
                    'segment', idx,
                    'createTime', now)
        end
        return {0, idx}
    end
end

-- 所有段都空了，兜底置售罄标记
redis.call('set', soldoutKey, '1')
redis.call('pexpireat', soldoutKey, expireAt)
return {1, 0}
