-- 补偿脚本：幂等恢复Redis库存 + 清除下单标记
-- 参数：voucherId, userId, orderId（可选）
local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]
local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId

-- 只有真正下过单（SET里存在）才恢复库存，防止重复补偿导致库存虚增
local removed = redis.call('srem', orderKey, userId)
if removed == 1 then
    redis.call('incrby', stockKey, 1)
end
if orderId and string.len(orderId) > 0 then
    redis.call('set', 'seckill:order:status:' .. orderId, 'FAILED', 'EX', 86400)
end
return removed
