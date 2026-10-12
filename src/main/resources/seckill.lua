-- Args:
-- ARGV[1] voucherId
-- ARGV[2] userId
-- ARGV[3] timestamp since custom epoch
-- ARGV[4] date suffix, yyyy:MM:dd
-- ARGV[5] queue partition count
local voucherId = ARGV[1]
local userId = ARGV[2]
local timestamp = ARGV[3]
local date = ARGV[4]
local partitionCount = tonumber(ARGV[5])

local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId
local orderIdKey = 'icr:order:' .. date

if (tonumber(redis.call('get', stockKey)) <= 0) then
    return '1'
end

if (redis.call('hexists', orderKey, userId) == 1) then
    return '2'
end

local count = redis.call('incr', orderIdKey)
local countStr = tostring(count)
while string.len(countStr) < 8 do
    countStr = '0' .. countStr
end
local orderId = timestamp .. countStr

local partition = tonumber(userId) % partitionCount
local queueKey = 'seckill:order:queue:' .. partition
local orderMessage = '{"orderId":' .. orderId ..
        ',"userId":' .. userId ..
        ',"voucherId":' .. voucherId .. '}'

redis.call('incrby', stockKey, -1)
redis.call('hset', orderKey, userId, orderId)
redis.call('rpush', queueKey, orderMessage)
redis.call('set', 'seckill:order:status:' .. orderId, 'PENDING', 'EX', 86400)

return '0:' .. orderId
