wrk.method = "POST"

local next_thread = 0

function setup(thread)
    thread:set("thread_index", next_thread)
    next_thread = next_thread + 1
end

function init(args)
    request_index = 0
    users_per_thread = tonumber(os.getenv("WRK_USERS_PER_THREAD")) or 20000
end

function request()
    request_index = request_index + 1
    if request_index > users_per_thread then
        error("Independent token pool exhausted; increase WRK_USERS_PER_THREAD and rerun in a clean test environment")
    end
    local token = string.format("wrk-%02d-%06d", thread_index, request_index)
    return wrk.format(nil, nil, {authorization = token})
end

function done(summary, latency, requests)
    io.write(string.format(
        "WRK_SUMMARY requests=%d elapsed_seconds=%.6f http_errors=%d connect_errors=%d read_errors=%d write_errors=%d timeouts=%d\n",
        summary.requests, summary.duration / 1000000, summary.errors.status,
        summary.errors.connect, summary.errors.read, summary.errors.write, summary.errors.timeout
    ))
end
