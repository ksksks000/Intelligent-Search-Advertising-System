-- 秒杀压测 wrk 脚本
-- 用法：
--   cp tokens.txt loadtest/
--   wrk -t8 -c500 -d60s --latency -s loadtest/seckill.lua http://127.0.0.1:8081
--
-- 说明：
--   1. tokens.txt 每行一个登录 token（由 VoucherOrderControllerTest#login 生成）。
--   2. 随机取 token，适合验证 QPS/超卖；一人一单请用 JMeter 固定线程 token。
--   3. VOUCHER_ID 可通过环境变量覆盖，默认 1。

local tokens = {}
local voucherId = os.getenv("VOUCHER_ID") or "1"

function init(args)
    math.randomseed(os.time())
    local file = io.open("tokens.txt", "r")
    if not file then
        error("tokens.txt not found in current directory")
    end
    for line in file:lines() do
        local token = line:gsub("%s+", "")
        if #token > 0 then
            tokens[#tokens + 1] = token
        end
    end
    file:close()
    if #tokens == 0 then
        error("tokens.txt is empty")
    end
end

request = function()
    local token = tokens[math.random(#tokens)]
    wrk.method = "POST"
    wrk.headers["authorization"] = token
    return wrk.format("POST", "/voucher-order/seckill/" .. voucherId)
end
