#!/usr/bin/env sh
set -eu

BASE_URL="${PAYGATE_BASE_URL:-${BASE_URL:-http://localhost:8080}}"
DOMAIN="${1:-example.com}"
CHECKS="${2:-dns}"

if [ -z "${PAYER_LND_REST_URL:-}" ]; then
  echo "PAYER_LND_REST_URL is required." >&2
  exit 2
fi

if [ -z "${PAYER_LND_MACAROON_HEX:-}" ]; then
  echo "PAYER_LND_MACAROON_HEX is required." >&2
  exit 2
fi

if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 is required to run the real-sats LND payer test." >&2
  exit 1
fi

python3 - "$BASE_URL" "$DOMAIN" "$CHECKS" <<'PY'
import base64
import json
import os
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request

base_url, domain, checks = sys.argv[1], sys.argv[2], sys.argv[3]
lnd_rest_url = os.environ["PAYER_LND_REST_URL"].rstrip("/")
lnd_macaroon = os.environ["PAYER_LND_MACAROON_HEX"]
tls_cert_path = os.environ.get("PAYER_LND_TLS_CERT_PATH", "").strip()

ssl_context = ssl.create_default_context(cafile=tls_cert_path) if tls_cert_path else None


def request(method, url, headers=None, body=None):
    data = None
    if body is not None:
        data = json.dumps(body, separators=(",", ":")).encode("utf-8")
        headers = {**(headers or {}), "Content-Type": "application/json"}
    req = urllib.request.Request(url, data=data, headers=headers or {}, method=method)
    try:
        with urllib.request.urlopen(req, timeout=60, context=ssl_context) as rsp:
            return rsp.status, dict(rsp.headers), rsp.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        return exc.code, dict(exc.headers), exc.read().decode("utf-8")


def b64url_decode(value):
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


def b64url_encode(raw):
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def preimage_to_hex(value):
    if not value:
        return ""
    if len(value) == 64 and all(c in "0123456789abcdefABCDEF" for c in value):
        return value.lower()
    try:
        raw = base64.b64decode(value)
    except Exception:
        return ""
    return raw.hex() if len(raw) == 32 else ""


report_url = (
    f"{base_url.rstrip()}/api/v1/trust/report?"
    + urllib.parse.urlencode({"domain": domain, "checks": checks})
)
lnd_headers = {"Grpc-Metadata-macaroon": lnd_macaroon}

print(f"Requesting Paygate challenge: {report_url}", file=sys.stderr)
status, headers, body = request("GET", report_url)
if status != 402:
    print(f"Expected 402 Payment Required, got HTTP {status}.", file=sys.stderr)
    print(body, file=sys.stderr)
    raise SystemExit(1)

challenge_body = json.loads(body)
payment_challenge = challenge_body["protocols"]["Payment"]
charge_request = json.loads(b64url_decode(payment_challenge["request"]))
method_details = charge_request["methodDetails"]
bolt11 = method_details["invoice"]
amount = int(charge_request["amount"])

print(f"Paying {amount} sats from LND payer node.", file=sys.stderr)
status, headers, body = request(
    "POST",
    f"{lnd_rest_url}/v1/channels/transactions",
    headers=lnd_headers,
    body={
        "payment_request": bolt11,
        "amt": str(amount),
        "fee_limit": {"fixed": "10"},
    },
)
if status < 200 or status >= 300:
    print(f"LND pay invoice failed with HTTP {status}.", file=sys.stderr)
    print(body, file=sys.stderr)
    raise SystemExit(1)

payment_result = json.loads(body)
payment_error = payment_result.get("payment_error")
if payment_error:
    print(f"LND payment failed: {payment_error}", file=sys.stderr)
    raise SystemExit(1)

preimage_hex = preimage_to_hex(payment_result.get("payment_preimage", ""))
if not preimage_hex:
    print("LND payment response did not include a usable payment_preimage.", file=sys.stderr)
    print(json.dumps({k: payment_result.get(k) for k in sorted(payment_result.keys())}, indent=2), file=sys.stderr)
    raise SystemExit(1)

credential = {
    "challenge": payment_challenge,
    "source": "lnd-real-sats-test",
    "payload": {"preimage": preimage_hex},
}
credential_blob = b64url_encode(json.dumps(credential, separators=(",", ":")).encode("utf-8"))

print("Retrying Paygate request with Authorization: Payment <credential>.", file=sys.stderr)
status, headers, body = request(
    "GET",
    report_url,
    headers={"Authorization": f"Payment {credential_blob}"},
)
print(f"HTTP {status}")
payment_receipt = headers.get("Payment-Receipt")
if payment_receipt:
    print(f"Payment-Receipt: {payment_receipt}")
print(body)

if status != 200:
    raise SystemExit(1)
PY
