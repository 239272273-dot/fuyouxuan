#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
cd "$PROJECT_ROOT"

HTTP_PORT=${HTTP_PORT:-$(sed -n 's/^HTTP_PORT=//p' .env 2>/dev/null | tail -1)}
HTTP_PORT=${HTTP_PORT:-8080}
BASE_URL="http://127.0.0.1:${HTTP_PORT}/api"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

assert_success() {
  local json=$1 label=$2
  [[ $(jq -r '.success' <<<"$json") == true ]] || fail "$label: $(jq -r '.errorMsg // .' <<<"$json")"
}

login() {
  local phone=$1 code_response code login_response token
  code_response=$(curl -fsS -X POST "$BASE_URL/user/code?phone=$phone")
  assert_success "$code_response" "request code for $phone"
  code=$(jq -r '.data' <<<"$code_response")
  login_response=$(curl -fsS -H 'Content-Type: application/json' \
    -d "{\"phone\":\"$phone\",\"code\":\"$code\"}" "$BASE_URL/user/login")
  assert_success "$login_response" "login $phone"
  token=$(jq -r '.data' <<<"$login_response")
  [[ -n "$token" && "$token" != null ]] || fail "empty token for $phone"
  echo "$token"
}

redis_command() {
  docker compose exec -T redis-master redis-cli --raw "$@" | tr -d '\r'
}

echo "[1/7] Infrastructure, Redis replication and both backends"
docker compose ps --status running
docker compose exec -T app-1 curl -fsS http://127.0.0.1:8080/shop-type/list | jq -e '.success == true' >/dev/null
docker compose exec -T app-2 curl -fsS http://127.0.0.1:8080/shop-type/list | jq -e '.success == true' >/dev/null
redis_command INFO replication | grep -q '^connected_slaves:1$' || fail "Redis replica is not connected"
sentinel_master=$(docker compose exec -T redis-sentinel-1 redis-cli -p 26379 --raw SENTINEL get-master-addr-by-name mymaster | head -1 | tr -d '\r')
[[ "$sentinel_master" == redis-master ]] || fail "Sentinel cannot resolve redis-master"
cluster=""
for _ in $(seq 1 15); do
  if cluster=$(docker compose exec -T rocketmq-master sh /home/rocketmq/rocketmq-4.9.7/bin/mqadmin clusterList -n rocketmq-namesrv:9876 2>/dev/null) \
      && grep -q 'broker-a' <<<"$cluster"; then
    break
  fi
  sleep 2
done
grep -q 'broker-a' <<<"$cluster" || fail "RocketMQ broker cluster is not registered"

echo "[2/7] Login and token refresh"
publisher_token=$(login 13686869696)
follower_token=$(login 13838411438)
me=$(curl -fsS -H "authorization: $follower_token" "$BASE_URL/user/me")
assert_success "$me" "authenticated /user/me"
[[ $(jq -r '.data.nickName' <<<"$me") == *研发中心* ]] || fail "enterprise employee seed data is missing"
follower_id=$(jq -r '.data.id' <<<"$me")

echo "[3/7] Nginx hot-benefit cache"
headers=$(mktemp)
curl -fsS -D "$headers" -o /dev/null "$BASE_URL/voucher/list/1"
curl -fsS -D "$headers" -o /dev/null "$BASE_URL/voucher/list/1"
grep -qi '^X-Fuyouxuan-Cache: HIT' "$headers" || fail "Nginx voucher cache did not return HIT"
rm -f "$headers"

echo "[4/7] Follow, publish, feed push and like"
followed=$(curl -fsS -H "authorization: $follower_token" "$BASE_URL/follow/or/not/1")
assert_success "$followed" "query follow state"
if [[ $(jq -r '.data' <<<"$followed") != true ]]; then
  follow_result=$(curl -fsS -X PUT -H "authorization: $follower_token" "$BASE_URL/follow/1/true")
  assert_success "$follow_result" "follow publisher"
fi
title="系统联调公告 $(date +%H:%M:%S)"
publish_payload=$(jq -nc --arg title "$title" '{shopId:1,title:$title,content:"这是一条用于验证关注流推送的企业内部信息。",images:"/assets/company/engineering-share.svg"}')
publish_result=$(curl -fsS -H "authorization: $publisher_token" -H 'Content-Type: application/json' -d "$publish_payload" "$BASE_URL/blog")
assert_success "$publish_result" "publish company post"
blog_id=$(jq -r '.data' <<<"$publish_result")
feed=$(curl -fsS -H "authorization: $follower_token" "$BASE_URL/blog/of/follow?lastId=$(date +%s%3N)&offset=0")
assert_success "$feed" "read following feed"
jq -e --argjson id "$blog_id" '.data.list | map(.id) | index($id) != null' <<<"$feed" >/dev/null || fail "published post was not pushed to follower feed"
like_result=$(curl -fsS -X PUT -H "authorization: $follower_token" "$BASE_URL/blog/like/$blog_id")
assert_success "$like_result" "like post"
for _ in $(seq 1 30); do
  [[ -n $(redis_command ZSCORE "blog:liked:$blog_id" "$follower_id") ]] && break
  sleep 0.5
done
[[ -n $(redis_command ZSCORE "blog:liked:$blog_id" "$follower_id") ]] || fail "Canal did not synchronize the new like into Redis"

echo "[5/7] Seckill acceptance and asynchronous RocketMQ order creation"
buyer_phone=$(printf '135%08d' $(( $(date +%s) % 100000000 )))
buyer_token=$(login "$buyer_phone")
seckill_result=$(curl -fsS -X POST -H "authorization: $buyer_token" "$BASE_URL/voucher-order/seckill/1")
assert_success "$seckill_result" "submit seckill"
order_id=$(jq -r '.data' <<<"$seckill_result")
order_state=PENDING
for _ in $(seq 1 45); do
  status_result=$(curl -fsS -H "authorization: $buyer_token" "$BASE_URL/voucher-order/$order_id/status")
  assert_success "$status_result" "query order status"
  order_state=$(jq -r '.data.status' <<<"$status_result")
  [[ "$order_state" == SUCCESS || "$order_state" == FAILED ]] && break
  sleep 1
done
[[ "$order_state" == SUCCESS ]] || fail "order $order_id ended in $order_state"

echo "[6/7] Canal invalidation across Redis and both Caffeine instances"
docker compose exec -T app-1 curl -fsS http://127.0.0.1:8080/shop/1 | jq -e '.success == true' >/dev/null
docker compose exec -T app-2 curl -fsS http://127.0.0.1:8080/shop/1 | jq -e '.success == true' >/dev/null
[[ $(redis_command EXISTS cache:shop:1) == 1 ]] || fail "shop cache was not populated"
docker compose exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "UPDATE fuyouxuan.tb_shop SET score=IF(score=50,49,50) WHERE id=1"' >/dev/null
expected_score=$(docker compose exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -Nse "SELECT score FROM fuyouxuan.tb_shop WHERE id=1"' | tr -d '\r')
for _ in $(seq 1 30); do
  [[ $(redis_command EXISTS cache:shop:1) == 0 ]] && break
  sleep 0.5
done
[[ $(redis_command EXISTS cache:shop:1) == 0 ]] || fail "Canal did not invalidate cache:shop:1"
for app in app-1 app-2; do
  actual_score=""
  for _ in $(seq 1 30); do
    actual_score=$(docker compose exec -T "$app" curl -fsS http://127.0.0.1:8080/shop/1 | jq -r '.data.score')
    [[ "$actual_score" == "$expected_score" ]] && break
    sleep 0.5
  done
  [[ "$actual_score" == "$expected_score" ]] || fail "$app kept stale Caffeine data: expected $expected_score, got $actual_score"
done

echo "[7/7] Frontend branding and local assets"
page=$(curl -fsS "http://127.0.0.1:${HTTP_PORT}/")
grep -q '福优选' <<<"$page" || fail "FuYouXuan branding is missing"
! grep -q '黑马点评' <<<"$page" || fail "old branding remains"
! grep -qi 'admin' <<<"$page" || fail "admin UI remains"
curl -fsS -o /dev/null "http://127.0.0.1:${HTTP_PORT}/assets/company/hr-announcement.svg"

echo
echo "PASS: login, Nginx cache, follow/feed, publish, like, seckill/order, Canal invalidation, Redis replication and frontend assets"
echo "Created verification order: $order_id"
echo "Created verification post:  $blog_id"
