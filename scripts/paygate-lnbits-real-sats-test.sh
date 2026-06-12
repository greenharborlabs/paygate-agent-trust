#!/usr/bin/env sh
set -eu

BASE_URL="${PAYGATE_BASE_URL:-${BASE_URL:-http://localhost:8080}}"
DOMAIN="${1:-example.com}"
CHECKS="${2:-dns}"

if [ -z "${PAYER_LNBITS_URL:-}" ]; then
  echo "PAYER_LNBITS_URL is required." >&2
  exit 2
fi

if [ -z "${PAYER_LNBITS_ADMIN_KEY:-}" ]; then
  echo "PAYER_LNBITS_ADMIN_KEY is required." >&2
  exit 2
fi

if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 is required to run the real-sats LNbits payer test." >&2
  exit 1
fi

python3 - "$BASE_URL" "$DOMAIN" "$CHECKS" <<'PY'
import base64
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

base_url, domain, checks = sys.argv[1], sys.argv[2], sys.argv[3]
payer_lnbits_url = os.environ["PAYER_LNBITS_URL"].rstrip("/")
payer_lnbits_admin_key = os.environ["PAYER_LNBITS_ADMIN_KEY"]


def request(method, url, headers=None, body=None):
    data = None
    if body is not None:
        data = json.dumps(body, separators=(",", ":")).encode("utf-8")
        headers = {**(headers or {}), "Content-Type": "application/json"}
    req = urllib.request.Request(url, data=data, headers=headers or {}, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as rsp:
            return rsp.status, dict(rsp.headers), rsp.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        return exc.code, dict(exc.headers), exc.read().decode("utf-8")


def b64url_decode(value):
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


def b64url_encode(raw):
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


report_url = (
    f"{base_url.rstrip()}/api/v1/trust/report?"
    + urllib.parse.urlencode({"domain": domain, "checks": checks})
)

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
payment_hash = method_details["paymentHash"]
amount = charge_request["amount"]

print(f"Paying {amount} sats from payer LNbits wallet.", file=sys.stderr)
status, headers, body = request(
    "POST",
    f"{payer_lnbits_url}/api/v1/payments",
    headers={"X-Api-Key": payer_lnbits_admin_key},
    body={"out": True, "bolt11": bolt11},
)
if status < 200 or status >= 300:
    print(f"LNbits pay invoice failed with HTTP {status}.", file=sys.stderr)
    print(body, file=sys.stderr)
    raise SystemExit(1)

preimage = None
for attempt in range(30):
    status, headers, body = request(
        "GET",
        f"{payer_lnbits_url}/api/v1/payments/{payment_hash}",
        headers={"X-Api-Key": payer_lnbits_admin_key},
    )
    if status < 200 or status >= 300:
        print(f"LNbits payment lookup failed with HTTP {status}.", file=sys.stderr)
        print(body, file=sys.stderr)
        raise SystemExit(1)
    lookup = json.loads(body)
    preimage = lookup.get("preimage")
    if preimage:
        break
    time.sleep(1)

if not preimage:
    print("LNbits payment settled status did not expose a preimage before timeout.", file=sys.stderr)
    print("Cannot build Paygate Authorization credential without the payment preimage.", file=sys.stderr)
    raise SystemExit(1)

credential = {
    "challenge": payment_challenge,
    "source": "lnbits-real-sats-test",
    "payload": {"preimage": preimage.lower()},
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
