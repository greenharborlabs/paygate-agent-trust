#!/usr/bin/env sh
set -eu

BASE_URL="${PAYGATE_BASE_URL:-http://localhost:8080}"
DOMAIN="${1:-}"
CHECKS="${2:-dns}"

if [ -z "$DOMAIN" ]; then
  echo "Usage: $0 <domain> [checks]" >&2
  echo "Example: $0 example.com dns,tls,http,robots" >&2
  exit 2
fi

if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 is required to build the local Paygate test credential." >&2
  exit 1
fi

if ! command -v curl >/dev/null 2>&1; then
  echo "curl is required to call the local service." >&2
  exit 1
fi

CHALLENGE_FILE="$(mktemp "${TMPDIR:-/tmp}/paygate-challenge.XXXXXX")"
trap 'rm -f "$CHALLENGE_FILE"' EXIT INT TERM

REPORT_URL="$BASE_URL/api/v1/trust/report"

echo "Requesting Paygate challenge for $DOMAIN checks=$CHECKS" >&2
STATUS="$(
  curl -sS -o "$CHALLENGE_FILE" -w "%{http_code}" --get "$REPORT_URL" \
    --data-urlencode "domain=$DOMAIN" \
    --data-urlencode "checks=$CHECKS"
)"

if [ "$STATUS" != "402" ]; then
  echo "Expected 402 Payment Required, got HTTP $STATUS." >&2
  echo "Response body:" >&2
  cat "$CHALLENGE_FILE" >&2
  exit 1
fi

PAYGATE_TEST_CREDENTIAL="$(
  python3 - "$CHALLENGE_FILE" <<'PY'
import base64
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as f:
    body = json.load(f)

try:
    credential = {
        "challenge": body["protocols"]["Payment"],
        "source": "local-dev",
        "payload": {"preimage": body["test_preimage"]},
    }
except KeyError as exc:
    raise SystemExit(f"Challenge body is missing expected test-mode field: {exc}") from exc

encoded = base64.urlsafe_b64encode(json.dumps(credential, separators=(",", ":")).encode())
print(encoded.decode().rstrip("="))
PY
)"

echo "Retrying with local test Payment credential" >&2
curl -i -sS --get "$REPORT_URL" \
  -H "Authorization: Payment $PAYGATE_TEST_CREDENTIAL" \
  --data-urlencode "domain=$DOMAIN" \
  --data-urlencode "checks=$CHECKS"
