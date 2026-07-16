#!/usr/bin/env bash
set -euo pipefail

IMAGE="${IMAGE:-paygate-agent-trust:smoke}"
PORT="${PORT:-18080}"
CONTAINER_NAME="paygate-agent-trust-smoke-$$"
PRIVATE_KEY="MC4CAQAwBQYDK2VwBCIEIKFgoMB34QYC1lTcyWsgFIJcqRY2cNcV2dMHbGGmPvhD"
PUBLIC_KEY="MCowBQYDK2VwAyEAgeVa2jClnW2JYB9MQVL1J0zsIrzv7QMneV5avr19sHM="

cleanup() {
  docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

docker build --tag "$IMAGE" .
docker run --detach --name "$CONTAINER_NAME" --publish "127.0.0.1:${PORT}:8080" \
  --env PAYGATE_ENABLED=true \
  --env PAYGATE_TEST_MODE=true \
  --env PAYGATE_ROOT_KEY_STORE=file \
  --env SPRING_PROFILES_ACTIVE=local \
  --env PAYGATE_PROTOCOLS_MPP_CHALLENGE_BINDING_SECRET=container-smoke-binding-secret-32-bytes \
  --env REPORT_SIGNING_PRIVATE_KEY="$PRIVATE_KEY" \
  --env REPORT_SIGNING_PUBLIC_KEY="$PUBLIC_KEY" \
  --env REPORT_SIGNING_KEY_ID=container-smoke \
  "$IMAGE" >/dev/null

base_url="http://127.0.0.1:${PORT}"
for _ in $(seq 1 60); do
  if curl --fail --silent "$base_url/healthz" >/dev/null; then break; fi
  sleep 1
done
curl --fail --silent "$base_url/healthz" | grep -q '"status":"ok"'
curl --fail --silent "$base_url/api/v1/catalog" | grep -q '"keyId":"container-smoke"'
curl --fail --silent "$base_url/api/v1/verification/keys" | grep -q '"kid":"container-smoke"'
curl --fail --silent "$base_url/api/v1/trust/quote?domain=example.com&checks=dns" | grep -q '"priceSats":10'

status="$(curl --silent --output /tmp/paygate-smoke-challenge.json --write-out '%{http_code}' \
  "$base_url/api/v1/trust/report?domain=example.com&checks=dns")"
test "$status" = "402"
grep -q '"protocols"' /tmp/paygate-smoke-challenge.json

test "$(docker inspect --format '{{.Config.User}}' "$CONTAINER_NAME")" = "10001:10001"
test -n "$(docker exec "$CONTAINER_NAME" find /home/app/.paygate/keys -type f -print -quit)"
docker stop --time 30 "$CONTAINER_NAME" >/dev/null
exit_code="$(docker inspect --format '{{.State.ExitCode}}' "$CONTAINER_NAME")"
case "$exit_code" in
  0|143) ;;
  *) echo "Container exited unexpectedly with status $exit_code" >&2; exit 1 ;;
esac
