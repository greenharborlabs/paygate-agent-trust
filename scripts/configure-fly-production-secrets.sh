#!/usr/bin/env bash
set -euo pipefail

APP="paygate-agent-trust"
KEY_DIR="${PAYGATE_PRODUCTION_KEY_DIR:-$HOME/.local/share/greenharborlabs/paygate-agent-trust-production}"
KEY_ID="${REPORT_SIGNING_KEY_ID:-2026-07-prod}"
AUTO_CONFIRM="false"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

LNBITS_URL=""
LNBITS_API_KEY=""
BINDING_SECRET=""
SIGNING_PRIVATE_KEY=""
SIGNING_PUBLIC_KEY=""

usage() {
  cat <<'EOF'
Usage: scripts/configure-fly-production-secrets.sh [options]

Stages the Paygate Agent Trust production secrets in Fly without deploying.
Secret values are read from permission-restricted key files or hidden prompts;
they are never accepted as command-line arguments.

Options:
  --app APP          Fly app name (default: paygate-agent-trust)
  --key-dir DIR      Directory containing generated Base64 key files
  --key-id ID        Report signing key ID (default: 2026-07-prod)
  --yes              Skip the final target confirmation
  -h, --help         Show this help

Expected files under --key-dir:
  report-signing-private.pkcs8.b64
  report-signing-public.x509.b64
  mpp-challenge-binding-secret.b64

If a file is absent, the script prompts for that value using hidden input so it
can be restored directly from an encrypted password manager such as Bitwarden.
EOF
}

cleanup() {
  unset LNBITS_URL LNBITS_API_KEY BINDING_SECRET
  unset SIGNING_PRIVATE_KEY SIGNING_PUBLIC_KEY
}
trap cleanup EXIT

fail() {
  printf 'Error: %s\n' "$1" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "$1 is required."
}

prompt_value() {
  local label="$1"
  local value

  read -r -p "$label: " value </dev/tty
  printf '%s' "$value"
}

prompt_secret() {
  local label="$1"
  local value

  read -r -s -p "$label (input hidden): " value </dev/tty
  printf '\n' >&2
  printf '%s' "$value"
}

load_secret_or_prompt() {
  local path="$1"
  local label="$2"
  local mode

  if [ -f "$path" ]; then
    [ ! -L "$path" ] || fail "$path must not be a symbolic link."
    mode="$(stat -f '%Lp' "$path" 2>/dev/null || stat -c '%a' "$path")"
    [[ "$mode" =~ ^[0-7]+00$ ]] \
      || fail "$path must not be readable or writable by group or other users."
    tr -d '\r\n' < "$path"
  else
    prompt_secret "$label"
  fi
}

validate_base64() {
  local name="$1"
  local value="$2"

  printf '%s' "$value" | base64 --decode >/dev/null 2>&1 \
    || fail "$name is not valid Base64."
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --app)
      [ "$#" -ge 2 ] || fail "--app requires a value."
      APP="$2"
      shift 2
      ;;
    --key-dir)
      [ "$#" -ge 2 ] || fail "--key-dir requires a value."
      KEY_DIR="$2"
      shift 2
      ;;
    --key-id)
      [ "$#" -ge 2 ] || fail "--key-id requires a value."
      KEY_ID="$2"
      shift 2
      ;;
    --yes)
      AUTO_CONFIRM="true"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      fail "unknown option: $1"
      ;;
  esac
done

[ -n "$APP" ] || fail "Fly app name must not be empty."
[ -n "$KEY_ID" ] || fail "report signing key ID must not be empty."
[[ "$KEY_ID" =~ ^[A-Za-z0-9._-]+$ ]] \
  || fail "report signing key ID may contain only letters, numbers, dot, underscore, and hyphen."

require_command flyctl
require_command openssl
require_command base64

flyctl auth whoami >/dev/null
flyctl config validate --strict --config "$REPO_ROOT/fly.toml" >/dev/null

printf '\nFly production secret staging\n'
printf '  App:    %s\n' "$APP"
printf '  Key ID: %s\n' "$KEY_ID"
printf '  Mode:   stage only (no deployment)\n\n'

flyctl status -a "$APP"

if [ "$AUTO_CONFIRM" != "true" ]; then
  confirmation="$(prompt_value "Stage production secrets for $APP? Type yes to continue")"
  [ "$confirmation" = "yes" ] || fail "cancelled; no secrets were changed."
fi

LNBITS_URL="$(prompt_value "LNbits root HTTPS URL, without /api")"
LNBITS_URL="${LNBITS_URL%/}"
[[ "$LNBITS_URL" == https://* ]] || fail "LNbits URL must begin with https://."

LNBITS_API_KEY="$(prompt_secret "LNbits payee wallet API key")"
[ -n "$LNBITS_API_KEY" ] || fail "LNbits API key must not be empty."

BINDING_SECRET="$(load_secret_or_prompt \
  "$KEY_DIR/mpp-challenge-binding-secret.b64" \
  "MPP challenge binding secret")"
SIGNING_PRIVATE_KEY="$(load_secret_or_prompt \
  "$KEY_DIR/report-signing-private.pkcs8.b64" \
  "Base64 PKCS#8 report signing private key")"
SIGNING_PUBLIC_KEY="$(load_secret_or_prompt \
  "$KEY_DIR/report-signing-public.x509.b64" \
  "Base64 X.509 report signing public key")"

[ -n "$BINDING_SECRET" ] || fail "MPP challenge binding secret must not be empty."
[ -n "$SIGNING_PRIVATE_KEY" ] || fail "report signing private key must not be empty."
[ -n "$SIGNING_PUBLIC_KEY" ] || fail "report signing public key must not be empty."

validate_base64 "MPP challenge binding secret" "$BINDING_SECRET"
validate_base64 "report signing private key" "$SIGNING_PRIVATE_KEY"
validate_base64 "report signing public key" "$SIGNING_PUBLIC_KEY"

binding_bytes="$(printf '%s' "$BINDING_SECRET" | base64 --decode | wc -c | tr -d ' ')"
[ "$binding_bytes" -ge 32 ] || fail "MPP challenge binding secret must decode to at least 32 bytes."

printf '%s' "$SIGNING_PRIVATE_KEY" \
  | base64 --decode \
  | openssl pkey -inform DER -noout >/dev/null 2>&1 \
  || fail "report signing private key is not valid PKCS#8 DER."

printf '%s' "$SIGNING_PUBLIC_KEY" \
  | base64 --decode \
  | openssl pkey -pubin -inform DER -noout >/dev/null 2>&1 \
  || fail "report signing public key is not valid X.509 DER."

derived_public_key="$(
  printf '%s' "$SIGNING_PRIVATE_KEY" \
    | base64 --decode \
    | openssl pkey -inform DER -pubout -outform DER \
    | base64 \
    | tr -d '\r\n'
)"
[ "$derived_public_key" = "$SIGNING_PUBLIC_KEY" ] \
  || fail "report signing private and public keys do not match."
unset derived_public_key binding_bytes

{
  printf '%s\n' \
    'SPRING_PROFILES_ACTIVE=prod' \
    'PAYGATE_ENABLED=true' \
    'PAYGATE_BACKEND=lnbits' \
    "PAYGATE_LNBITS_URL=$LNBITS_URL" \
    "PAYGATE_LNBITS_API_KEY=$LNBITS_API_KEY" \
    "PAYGATE_PROTOCOLS_MPP_CHALLENGE_BINDING_SECRET=$BINDING_SECRET" \
    "REPORT_SIGNING_PRIVATE_KEY=$SIGNING_PRIVATE_KEY" \
    "REPORT_SIGNING_PUBLIC_KEY=$SIGNING_PUBLIC_KEY" \
    "REPORT_SIGNING_KEY_ID=$KEY_ID"
} | flyctl secrets import --stage -a "$APP"

printf '\nStaged production secret names (values remain hidden):\n'
flyctl secrets list -a "$APP"
printf '\nNo deployment was triggered.\n'
