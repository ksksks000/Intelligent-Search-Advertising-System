-- 广告定向倒排求交：city / device / hour / interest 四组 Set，单次 Redis 往返完成。
-- KEYS: [cityKeys...][deviceKeys...][hourKeys...][interestKeys...]
-- ARGV[1]=cityCount, ARGV[2]=deviceCount, ARGV[3]=hourCount, ARGV[4]=interestCount

local cityCount = tonumber(ARGV[1])
local deviceCount = tonumber(ARGV[2])
local hourCount = tonumber(ARGV[3])
local interestCount = tonumber(ARGV[4])
local keyIndex = 1

local function unionSet(count)
    if count <= 0 then
        return {}
    end
    local keys = {}
    for i = 1, count do
        keys[i] = KEYS[keyIndex]
        keyIndex = keyIndex + 1
    end
    local values = redis.call('SUNION', unpack(keys))
    local set = {}
    for _, value in ipairs(values) do
        set[value] = true
    end
    return set
end

local city = unionSet(cityCount)
local device = unionSet(deviceCount)
local hour = unionSet(hourCount)
local interest = unionSet(interestCount)

local result = {}
for id, _ in pairs(city) do
    if device[id] and hour[id] and (interestCount <= 0 or interest[id]) then
        table.insert(result, id)
    end
end
return result
