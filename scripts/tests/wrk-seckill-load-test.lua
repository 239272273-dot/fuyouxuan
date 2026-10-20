local original_getenv = os.getenv
local original_write = io.write
local captured_output = ""
wrk = {
    format = function(method, path, headers)
        return headers.authorization
    end
}
os.getenv = function(name)
    return "3"
end
dofile("scripts/wrk-seckill-load.lua")

local fake_thread = {
    set = function(self, name, value)
        _G[name] = value
    end
}
setup(fake_thread)
init({})
assert(request() == "wrk-00-000001")
assert(request() == "wrk-00-000002")
assert(request() == "wrk-00-000003")
local succeeded, failure = pcall(request)
assert(not succeeded and string.find(failure, "token pool exhausted", 1, true))

setup(fake_thread)
init({})
assert(request() == "wrk-01-000001")
os.getenv = function(name)
    return nil
end
init({})
assert(users_per_thread == 20000)
io.write = function(value)
    captured_output = captured_output .. value
end
done({requests = 12, duration = 1234567, errors = {status = 2, connect = 1, read = 0, write = 0, timeout = 3}}, nil, nil)
assert(string.find(captured_output, "requests=12 elapsed_seconds=1.234567", 1, true))
assert(string.find(captured_output, "http_errors=2 connect_errors=1", 1, true))
assert(string.find(captured_output, "timeouts=3", 1, true))
os.getenv = original_getenv
io.write = original_write
print("PASS: independent tokens, exhaustion and precise wrk summary")
