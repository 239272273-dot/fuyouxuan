wrk.method = "POST"

local next_thread = 0

function setup(thread)
    thread:set("thread_index", next_thread)
    next_thread = next_thread + 1
end

function init(args)
    request_index = 0
    users_per_thread = tonumber(os.getenv("WRK_USERS_PER_THREAD")) or 7500
end

function request()
    request_index = request_index + 1
    local user_slot = ((request_index - 1) % users_per_thread) + 1
    local token = string.format("wrk-%02d-%06d", thread_index, user_slot)
    return wrk.format(nil, nil, {authorization = token})
end
