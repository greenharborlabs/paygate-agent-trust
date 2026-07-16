#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:?BASE_URL is required}"
EXPECTED_KEY_ID="${EXPECTED_KEY_ID:?EXPECTED_KEY_ID is required}"
BASE_URL="${BASE_URL%/}"

curl --fail --silent "$BASE_URL/healthz" | grep -q '"status":"ok"'
curl --fail --silent "$BASE_URL/" | grep -q '"catalog":"https://paygate-agent-trust.fly.dev/api/v1/catalog"'
curl --fail --silent "$BASE_URL/" | grep -q '"github":"https://github.com/greenharborlabs/paygate-agent-trust"'
curl --fail --silent "$BASE_URL/" | grep -q '"documentation":"https://github.com/greenharborlabs/paygate-agent-trust#readme"'
curl --fail --silent "$BASE_URL/api/v1/catalog" | grep -q "\"keyId\":\"$EXPECTED_KEY_ID\""
curl --fail --silent "$BASE_URL/api/v1/verification/keys" | grep -q "\"kid\":\"$EXPECTED_KEY_ID\""
curl --fail --silent "$BASE_URL/api/v1/trust/quote?domain=example.com&checks=dns" | grep -q '"priceSats":10'
headers="$(mktemp)"
body="$(mktemp)"
trap 'rm -f "$headers" "$body"' EXIT
status="$(curl --silent --dump-header "$headers" --output "$body" --write-out '%{http_code}' \
  "$BASE_URL/api/v1/trust/report?domain=example.com&checks=dns")"
test "$status" = "402"
grep -qi '^www-authenticate:.*L402' "$headers"
grep -qi '^www-authenticate:.*Payment' "$headers"
grep -q '"protocols"' "$body"
