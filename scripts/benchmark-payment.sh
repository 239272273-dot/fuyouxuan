#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
cd "$PROJECT_ROOT"

USERS=${PAYMENT_USERS:-2000}
PARALLEL=${PAYMENT_CONCURRENCY:-400}
DUPLICATES=${PAYMENT_DUPLICATES:-3}
REQUEST_TIMEOUT=${PAYMENT_REQUEST_TIMEOUT:-30}
VOUCHER_ID=${PAYMENT_VOUCHER_ID:-900002}
USER_BASE=${PAYMENT_USER_BASE:-910000000}
ORDER_BASE=${PAYMENT_ORDER_BASE:-910000000000000}
HTTP_PORT=${HTTP_PORT:-$(sed -n 's/^HTTP_PORT=//p' .env 2>/dev/null | tail -1 | tr -d '\r')}
HTTP_PORT=${HTTP_PORT:-8080}
BASE_URL=${PAYMENT_BASE_URL:-http://127.0.0.1:$HTTP_PORT/api}
REQUESTS=$((USERS * DUPLICATES))
RESULT_DIR=$(mktemp -d /tmp/fuyouxuan-payment.XXXXXX)

mysql_exec() {
  docker compose exec -T mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" fuyouxuan' 2>/dev/null
}

mysql_query() {
  local query=$1
  docker compose exec -T mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -Nse "$1" fuyouxuan' sh "$query" 2>/dev/null | tr -d '\r'
}

redis_pipe() {
  docker compose exec -T redis-master redis-cli --pipe >/dev/null
}

cleanup() {
  set +e
  mysql_exec <<SQL
DELETE FROM tb_voucher_order WHERE id > $ORDER_BASE AND id <= $((ORDER_BASE + USERS));
DELETE FROM tb_user_info WHERE user_id > $USER_BASE AND user_id <= $((USER_BASE + USERS));
DELETE FROM tb_user WHERE id > $USER_BASE AND id <= $((USER_BASE + USERS));
DELETE FROM tb_voucher WHERE id = $VOUCHER_ID;
SQL
  awk -v users="$USERS" -v user_base="$USER_BASE" -v order_base="$ORDER_BASE" '
    function bulk(value) { printf "$%d\r\n%s\r\n", length(value), value }
    BEGIN {
      for (n = 1; n <= users; n++) {
        printf "*2\r\n"; bulk("UNLINK"); bulk("login:token:pay-" (user_base + n))
        printf "*2\r\n"; bulk("UNLINK"); bulk("seckill:order:status:" (order_base + n))
      }
    }
  ' | redis_pipe
  rm -rf -- "$RESULT_DIR"
}
trap cleanup EXIT

echo "Preparing $USERS users and unpaid orders..."
mysql_exec <<SQL
INSERT INTO tb_voucher (id, shop_id, title, sub_title, rules, pay_value, actual_value, type, status)
VALUES ($VOUCHER_ID, 1, 'payment benchmark voucher', '', '', 100, 100, 1, 1)
ON DUPLICATE KEY UPDATE pay_value = VALUES(pay_value);

INSERT INTO tb_user (id, phone, password, nick_name, icon)
SELECT $USER_BASE + n, CONCAT('198', LPAD(n, 8, '0')), '', CONCAT('pay-', n), ''
FROM (
  SELECT ones.n + tens.n * 10 + hundreds.n * 100 + thousands.n * 1000 + ten_thousands.n * 10000 AS n
  FROM
    (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) ones
    CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) tens
    CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) hundreds
    CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) thousands
    CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) ten_thousands
) numbers WHERE n BETWEEN 1 AND $USERS;

INSERT INTO tb_user_info (user_id, credits)
SELECT id, 500 FROM tb_user WHERE id > $USER_BASE AND id <= $((USER_BASE + USERS));

INSERT INTO tb_voucher_order (id, user_id, voucher_id, status)
SELECT $ORDER_BASE + n, $USER_BASE + n, $VOUCHER_ID, 1
FROM (
  SELECT ones.n + tens.n * 10 + hundreds.n * 100 + thousands.n * 1000 + ten_thousands.n * 10000 AS n
  FROM
    (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) ones
    CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) tens
    CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) hundreds
    CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) thousands
    CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) ten_thousands
) numbers WHERE n BETWEEN 1 AND $USERS;
SQL

awk -v users="$USERS" -v base="$USER_BASE" '
  function bulk(value) { printf "$%d\r\n%s\r\n", length(value), value }
  BEGIN {
    for (n = 1; n <= users; n++) {
      key = "login:token:pay-" (base + n)
      printf "*6\r\n"; bulk("HSET"); bulk(key); bulk("id"); bulk(base + n); bulk("nickName"); bulk("pay")
      printf "*3\r\n"; bulk("EXPIRE"); bulk(key); bulk("7200")
    }
  }
' | redis_pipe

echo "Running $REQUESTS payment requests ($USERS users, $DUPLICATES clicks each) at concurrency $PARALLEL..."
start_ns=$(date +%s%N)
export BASE_URL USERS USER_BASE ORDER_BASE RESULT_DIR REQUEST_TIMEOUT
seq 1 "$REQUESTS" | xargs -P "$PARALLEL" -I{} sh -c '
  request=$1
  user_index=$(( (request - 1) % USERS + 1 ))
  user_id=$((USER_BASE + user_index))
  order_id=$((ORDER_BASE + user_index))
  curl -sS -m "$REQUEST_TIMEOUT" -X POST -H "authorization: pay-$user_id" \
    -w "\nHTTP_STATUS:%{http_code}\n" \
    "$BASE_URL/voucher-order/$order_id/pay" > "$RESULT_DIR/$request.json" || true
' _ {}
end_ns=$(date +%s%N)

elapsed=$(awk -v start="$start_ns" -v end="$end_ns" 'BEGIN { printf "%.3f", (end-start)/1000000000 }')
qps=$(awk -v requests="$REQUESTS" -v elapsed="$elapsed" 'BEGIN { printf "%.2f", requests/elapsed }')
paid=$(mysql_query "SELECT COUNT(*) FROM tb_voucher_order WHERE id > $ORDER_BASE AND id <= $((ORDER_BASE + USERS)) AND status=2")
wrong_balance=$(mysql_query "SELECT COUNT(*) FROM tb_user_info WHERE user_id > $USER_BASE AND user_id <= $((USER_BASE + USERS)) AND credits<>499")
negative_balance=$(mysql_query "SELECT COUNT(*) FROM tb_user_info WHERE user_id > $USER_BASE AND user_id <= $((USER_BASE + USERS)) AND credits<0")
successful_http=$(grep -l '"success":true' "$RESULT_DIR"/*.json 2>/dev/null | wc -l | tr -d ' ')
http_statuses=$(grep -h '^HTTP_STATUS:' "$RESULT_DIR"/*.json 2>/dev/null | sort | uniq -c | xargs || true)
business_errors=$(grep -h '"success":false' "$RESULT_DIR"/*.json 2>/dev/null \
  | sed -n 's/.*"errorMsg":"\([^"]*\)".*/\1/p' | sort | uniq -c | sort -nr | head -5 | xargs || true)
tps=$(awk -v paid="$paid" -v elapsed="$elapsed" 'BEGIN { printf "%.2f", paid/elapsed }')

echo "PAYMENT_BENCHMARK_RESULT requests=$REQUESTS users=$USERS concurrency=$PARALLEL elapsed=${elapsed}s qps=$qps business_tps=$tps successful_responses=$successful_http paid_orders=$paid wrong_balances=$wrong_balance negative_balances=$negative_balance"
echo "PAYMENT_HTTP_STATUS ${http_statuses:-none}"
echo "PAYMENT_BUSINESS_ERRORS ${business_errors:-none}"

[[ "$paid" == "$USERS" ]]
[[ "$wrong_balance" == "0" ]]
[[ "$negative_balance" == "0" ]]
[[ "$successful_http" == "$REQUESTS" ]]
