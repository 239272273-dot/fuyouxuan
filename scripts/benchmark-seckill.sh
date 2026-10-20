#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
cd "$PROJECT_ROOT"

THREADS=${WRK_THREADS:-8}
CONNECTIONS=${WRK_CONNECTIONS:-400}
DURATION=${WRK_DURATION:-15s}
REQUEST_TIMEOUT=${WRK_TIMEOUT:-10s}
USERS_PER_THREAD=${WRK_USERS_PER_THREAD:-20000}
WAIT_FOR_TIMEOUTS=${WRK_WAIT_FOR_TIMEOUTS:-0}
VOUCHER_ID=${WRK_VOUCHER_ID:-900001}
USER_ID_BASE=${WRK_USER_ID_BASE:-900000000}
for parameter in THREADS CONNECTIONS USERS_PER_THREAD VOUCHER_ID USER_ID_BASE; do
  [[ ${!parameter} =~ ^[1-9][0-9]*$ ]] || {
    echo "$parameter must be a positive integer" >&2
    exit 1
  }
done
USER_COUNT=$((THREADS * USERS_PER_THREAD))
HTTP_PORT=${HTTP_PORT:-$(sed -n 's/^HTTP_PORT=//p' .env 2>/dev/null | tail -1 | tr -d '\r')}
HTTP_PORT=${HTTP_PORT:-8080}

if ((USER_COUNT > 999999 || CONNECTIONS < THREADS)); then
  echo "USER_COUNT must not exceed 999999, and CONNECTIONS must be at least THREADS" >&2
  exit 1
fi

for command in docker wrk awk sed tr seq mktemp; do
  command -v "$command" >/dev/null || {
    echo "Missing required command: $command" >&2
    exit 1
  }
done
docker info >/dev/null
docker compose version >/dev/null

mysql_exec() {
  docker compose exec -T mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" fuyouxuan' 2>/dev/null
}

mysql_query() {
  local query=$1
  docker compose exec -T mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -Nse "$1" fuyouxuan' sh "$query" 2>/dev/null | tr -d '\r'
}

redis_command() {
  docker compose exec -T redis-master redis-cli --raw "$@" | tr -d '\r'
}

redis_pipe() {
  docker compose exec -T redis-master redis-cli --pipe >/dev/null
}

queue_depth() {
  local total=0 partition
  for partition in $(seq 0 15); do
    total=$((total + $(redis_command LLEN "seckill:order:queue:$partition")))
  done
  echo "$total"
}

mq_diff() {
  docker compose exec -T rocketmq-master \
    sh /home/rocketmq/rocketmq-4.9.7/bin/mqadmin consumerProgress \
    -n rocketmq-namesrv:9876 -g seckill-order-consumer-group 2>/dev/null \
    | awk '/Diff Total:/ {print $3}' | tail -1
}

timeout_consumed() {
  docker compose exec -T rocketmq-master \
    sh /home/rocketmq/rocketmq-4.9.7/bin/mqadmin consumerProgress \
    -n rocketmq-namesrv:9876 -g seckill-order-timeout-consumer-group 2>/dev/null \
    | awk '$1 == "seckill-order-timeout-topic" {sum += $5} END {print sum + 0}'
}

cleanup() {
  set +e
  if [[ "$cleanup_ready" != 1 ]]; then
    echo "Benchmark failed; test data and pending messages were preserved for diagnosis. Order ID file: $ORDER_ID_FILE" >&2
    return
  fi
  echo "Cleaning benchmark-only users, orders, tokens and voucher..."

  mysql_query "SELECT id FROM tb_voucher_order WHERE voucher_id=$VOUCHER_ID" \
    | awk '
      function bulk(value) { printf "$%d\r\n%s\r\n", length(value), value }
      NF { key="seckill:order:status:" $1; printf "*2\r\n"; bulk("UNLINK"); bulk(key) }
    ' | redis_pipe

  if [[ -s "$ORDER_ID_FILE" ]]; then
    awk '
      function bulk(value) { printf "$%d\r\n%s\r\n", length(value), value }
      NF { key="seckill:order:status:" $1; printf "*2\r\n"; bulk("UNLINK"); bulk(key) }
    ' "$ORDER_ID_FILE" | redis_pipe
  fi

  awk -v threads="$THREADS" -v per="$USERS_PER_THREAD" '
    function bulk(value) { printf "$%d\r\n%s\r\n", length(value), value }
    BEGIN {
      for (thread = 0; thread < threads; thread++) {
        for (request = 1; request <= per; request++) {
          key = sprintf("login:token:wrk-%02d-%06d", thread, request)
          printf "*2\r\n"; bulk("UNLINK"); bulk(key)
        }
      }
    }
  ' | redis_pipe

  redis_command DEL "seckill:stock:$VOUCHER_ID" "seckill:order:$VOUCHER_ID" >/dev/null

  mysql_exec <<SQL
DELETE FROM tb_voucher_order WHERE voucher_id = $VOUCHER_ID;
DELETE FROM tb_seckill_voucher WHERE voucher_id = $VOUCHER_ID;
DELETE FROM tb_voucher WHERE id = $VOUCHER_ID;
DELETE FROM tb_user WHERE id > $USER_ID_BASE AND id <= $((USER_ID_BASE + USER_COUNT));
SQL
  rm -f -- "$ORDER_ID_FILE"
}

[[ $(queue_depth) == 0 && $(mq_diff) == 0 ]] || {
  echo "Pending orders exist. Wait for the existing workload to finish; use an isolated local test environment." >&2
  exit 1
}
[[ $(mysql_query "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=$VOUCHER_ID") == 0 ]] || {
  echo "Benchmark voucher already has orders. Inspect the previous run before reusing this test fixture." >&2
  exit 1
}
ORDER_ID_FILE=$(mktemp /tmp/fuyouxuan-wrk-order-ids.XXXXXX)
cleanup_ready=0
trap cleanup EXIT

echo "Preparing $USER_COUNT isolated users and tokens..."
mysql_exec <<SQL
DELETE FROM tb_voucher_order WHERE voucher_id = $VOUCHER_ID;
DELETE FROM tb_seckill_voucher WHERE voucher_id = $VOUCHER_ID;
DELETE FROM tb_voucher WHERE id = $VOUCHER_ID;
DELETE FROM tb_user WHERE id > $USER_ID_BASE AND id <= $((USER_ID_BASE + USER_COUNT));

INSERT INTO tb_voucher
  (id, shop_id, title, sub_title, rules, pay_value, actual_value, type, status)
VALUES
  ($VOUCHER_ID, 1, 'wrk pressure-test voucher', 'isolated local test data',
   'automatically removed after the benchmark', 1, 1, 1, 1);

INSERT INTO tb_seckill_voucher (voucher_id, stock, begin_time, end_time)
VALUES ($VOUCHER_ID, $USER_COUNT, '2026-01-01 00:00:00', '2035-12-31 23:59:59');

INSERT INTO tb_user (id, phone, password, nick_name, icon)
SELECT
  $USER_ID_BASE + n,
  CONCAT('199', LPAD(n, 8, '0')),
  '',
  CONCAT('wrk-', n),
  ''
FROM (
  SELECT ones.n + tens.n * 10 + hundreds.n * 100 + thousands.n * 1000
       + ten_thousands.n * 10000 + hundred_thousands.n * 100000 AS n
  FROM
    (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) ones
  CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) tens
  CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) hundreds
  CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) thousands
  CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) ten_thousands
  CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) hundred_thousands
) numbers
WHERE n BETWEEN 1 AND $USER_COUNT;
SQL

redis_command DEL "seckill:order:$VOUCHER_ID" >/dev/null
redis_command SET "seckill:stock:$VOUCHER_ID" "$USER_COUNT" >/dev/null

awk -v threads="$THREADS" -v per="$USERS_PER_THREAD" -v base="$USER_ID_BASE" '
  function bulk(value) { printf "$%d\r\n%s\r\n", length(value), value }
  BEGIN {
    for (thread = 0; thread < threads; thread++) {
      for (request = 1; request <= per; request++) {
        key = sprintf("login:token:wrk-%02d-%06d", thread, request)
        user = base + thread * per + request
        printf "*6\r\n"; bulk("HSET"); bulk(key); bulk("id"); bulk(user); bulk("nickName"); bulk("wrk")
        printf "*3\r\n"; bulk("EXPIRE"); bulk(key); bulk("7200")
      }
    }
  }
' | redis_pipe

echo "Running: wrk -t$THREADS -c$CONNECTIONS -d$DURATION --timeout $REQUEST_TIMEOUT -s scripts/wrk-seckill-load.lua http://127.0.0.1:$HTTP_PORT/api/voucher-order/seckill/$VOUCHER_ID"
timeout_consumed_before=$(timeout_consumed)
export WRK_USERS_PER_THREAD="$USERS_PER_THREAD"
wrk_exit=0
wrk_output=$(wrk -t"$THREADS" -c"$CONNECTIONS" -d"$DURATION" --timeout "$REQUEST_TIMEOUT" \
  -s scripts/wrk-seckill-load.lua \
  "http://127.0.0.1:$HTTP_PORT/api/voucher-order/seckill/$VOUCHER_ID" 2>&1) || wrk_exit=$?
echo "$wrk_output"
if ((wrk_exit != 0)); then
  echo "wrk failed. This run is not a valid throughput result; inspect the output above." >&2
  exit "$wrk_exit"
fi

summary_value() {
  awk -v key="$1" '/^WRK_SUMMARY / { for (field = 2; field <= NF; field++) { split($field, pair, "="); if (pair[1] == key) print pair[2] } }' <<<"$wrk_output"
}

requests=$(summary_value requests)
qps=$(awk '/Requests\/sec:/ {print $2}' <<<"$wrk_output")
elapsed_seconds=$(summary_value elapsed_seconds)
[[ "$requests" =~ ^[0-9]+$ && "$elapsed_seconds" =~ ^[0-9]+\.[0-9]+$ ]] && \
  awk -v elapsed="$elapsed_seconds" 'BEGIN { exit !(elapsed > 0) }' || {
  echo "wrk did not produce a valid WRK_SUMMARY." >&2
  exit 1
}
accepted=$(redis_command HLEN "seckill:order:$VOUCHER_ID")
final_stock=$(redis_command GET "seckill:stock:$VOUCHER_ID")

echo "Waiting for Redis queues and RocketMQ to finish writing accepted orders..."
drain_started=$SECONDS
orders=0
diff=-1
depth=-1
while ((SECONDS - drain_started < 180)); do
  orders=$(mysql_query "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=$VOUCHER_ID")
  depth=$(queue_depth)
  diff=$(mq_diff)
  diff=${diff:-1}
  if [[ "$orders" == "$accepted" && "$depth" == 0 && "$diff" == 0 ]]; then
    break
  fi
  sleep 1
done

expected_stock=$((USER_COUNT - accepted))
accepted_per_sec=$(awk -v accepted="$accepted" -v elapsed="$elapsed_seconds" 'BEGIN { printf "%.2f", accepted/elapsed }')
mysql_stock=$(mysql_query "SELECT stock FROM tb_seckill_voucher WHERE voucher_id=$VOUCHER_ID")
echo "BENCHMARK_RESULT requests=$requests qps=$qps accepted_per_sec=$accepted_per_sec elapsed_seconds=$elapsed_seconds drain_seconds=$((SECONDS - drain_started)) accepted=$accepted mysql_orders=$orders redis_stock=$final_stock mysql_stock=$mysql_stock redis_queue_depth=$depth mq_diff=$diff"

[[ "$orders" == "$accepted" ]] || {
  echo "Accepted Redis orders and MySQL orders do not match" >&2
  exit 1
}
[[ "$final_stock" == "$expected_stock" ]] || {
  echo "Redis stock does not match accepted orders" >&2
  exit 1
}
[[ "$mysql_stock" == "$expected_stock" && "$depth" == 0 ]] || {
  echo "MySQL stock does not match accepted orders, or Redis still has pending orders" >&2
  exit 1
}
[[ "$diff" == 0 ]] || {
  echo "RocketMQ still has pending messages" >&2
  exit 1
}
((accepted > 0)) || {
  echo "No orders were accepted. Inspect the responses before reporting throughput." >&2
  exit 1
}

mysql_query "SELECT id FROM tb_voucher_order WHERE voucher_id=$VOUCHER_ID ORDER BY id" > "$ORDER_ID_FILE"

if [[ "$WAIT_FOR_TIMEOUTS" == "1" ]]; then
  timeout_target=$((timeout_consumed_before + accepted))
  echo "Waiting for delayed cancellations: target_consumed=$timeout_target..."
  timeout_current=$timeout_consumed_before
  remaining_orders=$orders
  timeout_wait_started=$SECONDS
  last_progress_report=$SECONDS
  while ((SECONDS - timeout_wait_started < 900)); do
    timeout_current=$(timeout_consumed)
    remaining_orders=$(mysql_query "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=$VOUCHER_ID")
    if ((SECONDS - last_progress_report >= 30)); then
      echo "TIMEOUT_PROGRESS elapsed=$((SECONDS - timeout_wait_started))s consumed=$timeout_current remaining_orders=$remaining_orders"
      last_progress_report=$SECONDS
    fi
    if ((timeout_current >= timeout_target)) && [[ "$remaining_orders" == "0" ]]; then
      break
    fi
    sleep 10
  done

  restored_mysql_stock=$(mysql_query "SELECT stock FROM tb_seckill_voucher WHERE voucher_id=$VOUCHER_ID")
  restored_redis_stock=$(redis_command GET "seckill:stock:$VOUCHER_ID")
  remaining_reservations=$(redis_command HLEN "seckill:order:$VOUCHER_ID")
  echo "TIMEOUT_RESULT consumed=$timeout_current remaining_orders=$remaining_orders mysql_stock=$restored_mysql_stock redis_stock=$restored_redis_stock reservations=$remaining_reservations"
  ((timeout_current >= timeout_target))
  [[ "$remaining_orders" == "0" ]]
  [[ "$restored_mysql_stock" == "$USER_COUNT" ]]
  [[ "$restored_redis_stock" == "$USER_COUNT" ]]
  [[ "$remaining_reservations" == "0" ]]
fi

cleanup_ready=1
