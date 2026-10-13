#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
cd "$PROJECT_ROOT"

USERS=${RACE_USERS:-500}
PARALLEL=${RACE_CONCURRENCY:-400}
VOUCHER_ID=${RACE_VOUCHER_ID:-900003}
USER_BASE=${RACE_USER_BASE:-920000000}
ORDER_BASE=${RACE_ORDER_BASE:-920000000000000}
HTTP_PORT=${HTTP_PORT:-$(sed -n 's/^HTTP_PORT=//p' .env 2>/dev/null | tail -1 | tr -d '\r')}
HTTP_PORT=${HTTP_PORT:-8080}
BASE_URL=${RACE_BASE_URL:-http://127.0.0.1:$HTTP_PORT/api}
REQUEST_TIMEOUT=${RACE_REQUEST_TIMEOUT:-30}
RESULT_DIR=$(mktemp -d /tmp/fuyouxuan-race.XXXXXX)

mysql_exec() { docker compose exec -T mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" fuyouxuan' 2>/dev/null; }
mysql_query() { local query=$1; docker compose exec -T mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -Nse "$1" fuyouxuan' sh "$query" 2>/dev/null | tr -d '\r'; }
redis_command() { docker compose exec -T redis-master redis-cli --raw "$@" | tr -d '\r'; }
redis_pipe() { docker compose exec -T redis-master redis-cli --pipe >/dev/null; }

cleanup() {
  set +e
  mysql_exec <<SQL
DELETE FROM tb_voucher_order WHERE id > $ORDER_BASE AND id <= $((ORDER_BASE + USERS));
DELETE FROM tb_user_info WHERE user_id > $USER_BASE AND user_id <= $((USER_BASE + USERS));
DELETE FROM tb_user WHERE id > $USER_BASE AND id <= $((USER_BASE + USERS));
DELETE FROM tb_seckill_voucher WHERE voucher_id=$VOUCHER_ID;
DELETE FROM tb_voucher WHERE id=$VOUCHER_ID;
SQL
  redis_command DEL "seckill:stock:$VOUCHER_ID" "seckill:order:$VOUCHER_ID" >/dev/null
  awk -v users="$USERS" -v user_base="$USER_BASE" -v order_base="$ORDER_BASE" '
    function bulk(value) { printf "$%d\r\n%s\r\n", length(value), value }
    BEGIN { for (n=1;n<=users;n++) {
      printf "*2\r\n"; bulk("UNLINK"); bulk("login:token:race-" (user_base+n))
      printf "*2\r\n"; bulk("UNLINK"); bulk("seckill:order:status:" (order_base+n))
    }}' | redis_pipe
  rm -rf -- "$RESULT_DIR"
}
trap cleanup EXIT

mysql_exec <<SQL
INSERT INTO tb_voucher (id,shop_id,title,sub_title,rules,pay_value,actual_value,type,status)
VALUES ($VOUCHER_ID,1,'race voucher','','',100,100,1,1);
INSERT INTO tb_seckill_voucher(voucher_id,stock,begin_time,end_time)
VALUES ($VOUCHER_ID,0,'2026-01-01','2035-12-31');
INSERT INTO tb_user(id,phone,password,nick_name,icon)
SELECT $USER_BASE+n, CONCAT('197',LPAD(n,8,'0')),'',CONCAT('race-',n),''
FROM (SELECT a.n+b.n*10+c.n*100+d.n*1000 AS n FROM
(SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9)a
CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9)b
CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9)c
CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9)d) x WHERE n BETWEEN 1 AND $USERS;
INSERT INTO tb_user_info(user_id,credits) SELECT id,500 FROM tb_user WHERE id>$USER_BASE AND id<=$((USER_BASE+USERS));
INSERT INTO tb_voucher_order(id,user_id,voucher_id,status)
SELECT $ORDER_BASE+(id-$USER_BASE),id,$VOUCHER_ID,1 FROM tb_user WHERE id>$USER_BASE AND id<=$((USER_BASE+USERS));
SQL

redis_command SET "seckill:stock:$VOUCHER_ID" 0 >/dev/null
awk -v users="$USERS" -v user_base="$USER_BASE" -v order_base="$ORDER_BASE" -v voucher="$VOUCHER_ID" '
  function bulk(value) { printf "$%d\r\n%s\r\n", length(value), value }
  BEGIN { for(n=1;n<=users;n++) {
    uid=user_base+n; oid=order_base+n; key="login:token:race-" uid
    printf "*6\r\n"; bulk("HSET"); bulk(key); bulk("id"); bulk(uid); bulk("nickName"); bulk("race")
    printf "*4\r\n"; bulk("HSET"); bulk("seckill:order:" voucher); bulk(uid); bulk(oid)
  }}' | redis_pipe

echo "Racing payment and cancellation for $USERS users at concurrency $PARALLEL..."
export USERS USER_BASE ORDER_BASE BASE_URL REQUEST_TIMEOUT RESULT_DIR
seq 1 $((USERS*2)) | xargs -P "$PARALLEL" -I{} sh -c '
  request=$1; user_index=$(( (request-1)/2 + 1 )); action=$(( (request-1)%2 ))
  uid=$((USER_BASE+user_index)); oid=$((ORDER_BASE+user_index))
  if [ "$action" -eq 0 ]; then endpoint=pay; else endpoint=cancel; fi
  curl -sS -m "$REQUEST_TIMEOUT" -X POST -H "authorization: race-$uid" \
    -w "\nHTTP_STATUS:%{http_code}\n" \
    "$BASE_URL/voucher-order/$oid/$endpoint" > "$RESULT_DIR/$request.json" || true
' _ {}

paid=$(mysql_query "SELECT COUNT(*) FROM tb_voucher_order WHERE id>$ORDER_BASE AND id<=$((ORDER_BASE+USERS)) AND status=2")
remaining=$(mysql_query "SELECT COUNT(*) FROM tb_voucher_order WHERE id>$ORDER_BASE AND id<=$((ORDER_BASE+USERS))")
cancelled=$((USERS-paid))
wrong_balance=$(mysql_query "SELECT COUNT(*) FROM tb_user_info i LEFT JOIN tb_voucher_order o ON o.user_id=i.user_id AND o.voucher_id=$VOUCHER_ID WHERE i.user_id>$USER_BASE AND i.user_id<=$((USER_BASE+USERS)) AND i.credits <> IF(o.status=2,499,500)")
mysql_stock=$(mysql_query "SELECT stock FROM tb_seckill_voucher WHERE voucher_id=$VOUCHER_ID")
redis_stock=$(redis_command GET "seckill:stock:$VOUCHER_ID")
redis_paid=$(redis_command HLEN "seckill:order:$VOUCHER_ID")
http_statuses=$(grep -h '^HTTP_STATUS:' "$RESULT_DIR"/*.json 2>/dev/null | sort | uniq -c | xargs || true)

echo "PAYMENT_RACE_RESULT users=$USERS paid=$paid cancelled=$cancelled remaining_orders=$remaining mysql_stock=$mysql_stock redis_stock=$redis_stock redis_paid_reservations=$redis_paid wrong_balances=$wrong_balance"
echo "PAYMENT_RACE_HTTP_STATUS ${http_statuses:-none}"
[[ "$http_statuses" == "$((USERS * 2)) HTTP_STATUS:200" ]]
[[ "$remaining" == "$paid" ]]
[[ "$mysql_stock" == "$cancelled" ]]
[[ "$redis_stock" == "$cancelled" ]]
[[ "$redis_paid" == "$paid" ]]
[[ "$wrong_balance" == "0" ]]
