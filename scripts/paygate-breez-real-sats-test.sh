#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${PAYGATE_BASE_URL:-https://paygate-agent-trust.fly.dev}"
DOMAIN="${1:-example.com}"
CHECKS="${2:-dns}"
CONFIG="${PAYGATE_CLIENT_CONFIG:-config/paygate-client-breez.yaml}"
PAYGATE_CLIENT_BIN="${PAYGATE_CLIENT_BIN:-paygate}"

: "${BREEZ_API_KEY:?BREEZ_API_KEY is required}"
: "${BREEZ_MNEMONIC:?BREEZ_MNEMONIC is required}"
command -v "$PAYGATE_CLIENT_BIN" >/dev/null || {
  echo 'Install the pinned client: python -m pip install "paygate-client[breez] @ git+https://github.com/greenharborlabs/paygate-client.git@684b6f556d0916643e2283ef66b8c21d776de6bd"' >&2
  exit 1
}

url="${BASE_URL%/}/api/v1/trust/report?domain=${DOMAIN}&checks=${CHECKS}"
result="$(mktemp)"
trap 'rm -f "$result"' EXIT
"$PAYGATE_CLIENT_BIN" request GET "$url" --config "$CONFIG" >"$result"

python3 - "$result" "${BASE_URL%/}/api/v1/trust/verify" <<'PY'
import json
import sys
import urllib.request

with open(sys.argv[1], encoding="utf-8") as stream:
    envelope = json.load(stream)
if not envelope.get("ok") or not envelope.get("paid"):
    raise SystemExit("Paygate client did not report a successful paid request")
response = envelope.get("response", {})
headers = {key.lower(): value for key, value in response.get("headers", {}).items()}
if "payment-receipt" not in headers:
    raise SystemExit("Paid response is missing Payment-Receipt")
report = response.get("json")
if not isinstance(report, dict) or "signature" not in report:
    raise SystemExit("Paid response is missing a signed report")
request = urllib.request.Request(
    sys.argv[2],
    data=json.dumps(report).encode(),
    headers={"Content-Type": "application/json"},
    method="POST",
)
with urllib.request.urlopen(request, timeout=30) as verification_response:
    verification = json.load(verification_response)
if not verification.get("valid"):
    raise SystemExit("Public report verification failed")
print(json.dumps({"paid": True, "reportVerified": True, "paymentHash": envelope.get("paymentHash")}, indent=2))
PY
