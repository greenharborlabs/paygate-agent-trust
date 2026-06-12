#!/usr/bin/env sh
set -eu

if [ -f ".env" ]; then
  set -a
  . ./.env
  set +a
fi

KEY_FILE="${REPORT_SIGNING_KEY_FILE:-report-signing-private.pem}"

if ! command -v openssl >/dev/null 2>&1; then
  echo "openssl is required to generate local Ed25519 signing keys." >&2
  return 1 2>/dev/null || exit 1
fi

if [ ! -f "$KEY_FILE" ]; then
  openssl genpkey -algorithm ED25519 -out "$KEY_FILE" >/dev/null 2>&1
  chmod 600 "$KEY_FILE"
fi

export REPORT_SIGNING_PRIVATE_KEY
REPORT_SIGNING_PRIVATE_KEY="$(openssl pkcs8 -topk8 -nocrypt -in "$KEY_FILE" -outform DER | base64 | tr -d '\n')"

export REPORT_SIGNING_PUBLIC_KEY
REPORT_SIGNING_PUBLIC_KEY="$(openssl pkey -in "$KEY_FILE" -pubout -outform DER | base64 | tr -d '\n')"

export REPORT_SIGNING_KEY_ID="${REPORT_SIGNING_KEY_ID:-local-dev}"
export PAYGATE_ENABLED="${PAYGATE_ENABLED:-false}"

echo "Local Paygate Agent Trust environment is ready."
echo "PAYGATE_ENABLED=$PAYGATE_ENABLED"
echo "REPORT_SIGNING_KEY_ID=$REPORT_SIGNING_KEY_ID"
