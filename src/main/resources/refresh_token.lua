-- KEYS[1] login token key
-- ARGV[1] ttl seconds
local values = redis.call('hgetall', KEYS[1])
if (not values or #values == 0) then
    return values
end
redis.call('expire', KEYS[1], tonumber(ARGV[1]))
return values
