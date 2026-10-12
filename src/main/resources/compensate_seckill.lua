-- 补偿脚本：只补偿当前订单，防止旧的重复消息误删用户的新订单
-- 参数：voucherId, userId, orderId, finalState（可选，默认FAILED）
local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]
local finalState = ARGV[4] or 'FAILED'
local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId

local currentOrderId = redis.call('hget', orderKey, userId)
local removed = 0
if currentOrderId == orderId then
    redis.call('hdel', orderKey, userId)
    redis.call('incrby', stockKey, 1)
    removed = 1
end
if orderId and string.len(orderId) > 0 then
    redis.call('set', 'seckill:order:status:' .. orderId, finalState, 'EX', 86400)
end
return removed
