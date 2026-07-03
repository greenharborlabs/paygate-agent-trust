#!/usr/bin/env bash
set -euo pipefail

ENV_PATH="${PAYGATE_AGENT_TRUST_ENV_PATH:-.env}"

DEFAULT_PAYGATE_ENABLED="true"
DEFAULT_PAYGATE_BACKEND="lnbits"
DEFAULT_PAYGATE_BASE_URL="http://localhost:8080"
DEFAULT_BASE_URL="http://localhost:8080"
DEFAULT_REPORT_SIGNING_KEY_ID="local-dev"
DEFAULT_PAYGATE_LIGHTNING_TIMEOUT_SECONDS="20"
DEFAULT_PAYGATE_LNBITS_REQUEST_TIMEOUT_SECONDS="20"
DEFAULT_PAYGATE_LNBITS_CONNECT_TIMEOUT_SECONDS="10"

prompt() {
  local label="$1"
  local default="$2"
  local value

  if [ -n "$default" ]; then
    read -r -p "$label [$default]: " value
    printf '%s' "${value:-$default}"
  else
    read -r -p "$label: " value
    printf '%s' "$value"
  fi
}

prompt_secret() {
  local label="$1"
  local value

  read -r -s -p "$label: " value
  printf '\n' >&2
  printf '%s' "$value"
}

prompt_paygate_backend() {
  local value

  printf '\nPaygate backend options:\n' >&2
  printf '  1) lnbits - use an LNbits payee wallet to create and verify invoices\n' >&2
  printf '  2) custom - enter a raw backend value for advanced/manual testing\n' >&2
  read -r -p "Select Paygate backend [1]: " value

  case "${value:-1}" in
    1|lnbits)
      printf 'lnbits'
      ;;
    2|custom)
      prompt "Custom PAYGATE_BACKEND value" ""
      ;;
    *)
      printf 'Error: unsupported backend selection: %s\n' "$value" >&2
      exit 1
      ;;
  esac
}

normalize_lnbits_url() {
  local raw="$1"
  local url="$raw"

  if [[ "$url" != http://* && "$url" != https://* ]]; then
    url="https://$url"
  fi
  url="${url%/}"
  printf '%s' "$url"
}

normalize_lnd_rest_url() {
  local raw="$1"
  local url="$raw"

  if [ -z "$url" ]; then
    printf ''
    return
  fi
  if [[ "$url" != http://* && "$url" != https://* ]]; then
    url="https://$url"
  fi
  if [[ "$url" != *:[0-9]* ]]; then
    url="$url:8080"
  fi
  printf '%s' "$url"
}

shell_quote() {
  printf "'%s'" "${1//\'/\'\\\'\'}"
}

require_non_empty() {
  local name="$1"
  local value="$2"

  if [ -z "$value" ]; then
    printf 'Error: %s is required.\n' "$name" >&2
    exit 1
  fi
}

require_bool() {
  local name="$1"
  local value="$2"

  if [[ "$value" != "true" && "$value" != "false" ]]; then
    printf 'Error: %s must be true or false.\n' "$name" >&2
    exit 1
  fi
}

require_non_negative_int() {
  local name="$1"
  local value="$2"

  if ! [[ "$value" =~ ^[0-9]+$ ]]; then
    printf 'Error: %s must be a non-negative integer.\n' "$name" >&2
    exit 1
  fi
}

require_hex_if_present() {
  local name="$1"
  local value="$2"

  if [ -z "$value" ]; then
    return
  fi
  if ! [[ "$value" =~ ^[0-9a-fA-F]+$ ]]; then
    printf 'Error: %s must be hex encoded.\n' "$name" >&2
    exit 1
  fi
  if (( ${#value} % 2 != 0 )); then
    printf 'Error: %s must have an even number of hex characters.\n' "$name" >&2
    exit 1
  fi
}

printf '\nPaygate Agent Trust local .env setup wizard\n'
printf 'This writes %s for local LNbits-backed Paygate testing.\n\n' "$ENV_PATH"

printf 'Checklist before continuing:\n'
printf '  1. You have an LNbits payee wallet for this service.\n'
printf '  2. You have the payee wallet API key that can create invoices and read payment status.\n'
printf '  3. For real-sats client testing, your Voltage LND node has outbound liquidity.\n'
printf '  4. You have the Voltage REST endpoint and macaroon hex if using scripts/paygate-lnd-real-sats-test.sh.\n\n'

paygate_enabled="$(prompt "Enable Paygate payment enforcement" "$DEFAULT_PAYGATE_ENABLED")"
paygate_backend="$(prompt_paygate_backend)"
lnbits_url=""
lnbits_api_key=""
if [ "$paygate_backend" = "lnbits" ]; then
  lnbits_url="$(normalize_lnbits_url "$(prompt "LNbits root URL, no /api suffix" "")")"
  lnbits_api_key="$(prompt_secret "LNbits payee wallet API key (input hidden)")"
fi
binding_secret="$(prompt_secret "MPP challenge binding secret, blank to generate")"

if [ -z "$binding_secret" ]; then
  if ! command -v openssl >/dev/null 2>&1; then
    printf 'Error: openssl is required to generate PAYGATE_PROTOCOLS_MPP_CHALLENGE_BINDING_SECRET.\n' >&2
    exit 1
  fi
  binding_secret="$(openssl rand -base64 32)"
fi

paygate_base_url="$(prompt "Helper script Paygate base URL" "$DEFAULT_PAYGATE_BASE_URL")"
base_url="$(prompt "General base URL" "$DEFAULT_BASE_URL")"
report_signing_key_id="$(prompt "Report signing key id" "$DEFAULT_REPORT_SIGNING_KEY_ID")"
lightning_timeout="$(prompt "Paygate Lightning timeout seconds" "$DEFAULT_PAYGATE_LIGHTNING_TIMEOUT_SECONDS")"
lnbits_request_timeout="$(prompt "LNbits request timeout seconds" "$DEFAULT_PAYGATE_LNBITS_REQUEST_TIMEOUT_SECONDS")"
lnbits_connect_timeout="$(prompt "LNbits connect timeout seconds" "$DEFAULT_PAYGATE_LNBITS_CONNECT_TIMEOUT_SECONDS")"

printf '\nOptional Voltage/LND payer settings for scripts/paygate-lnd-real-sats-test.sh.\n'
voltage_url="$(normalize_lnd_rest_url "$(prompt "Voltage LND REST URL, blank to skip" "")")"
voltage_macaroon=""
voltage_tls_cert_path=""
if [ -n "$voltage_url" ]; then
  voltage_macaroon="$(prompt_secret "Voltage macaroon hex (input hidden)")"
  voltage_tls_cert_path="$(prompt "TLS cert path, blank for Voltage hosted default" "")"
fi

printf '\nOptional LNbits payer settings for scripts/paygate-lnbits-real-sats-test.sh.\n'
payer_lnbits_url="$(normalize_lnbits_url "$(prompt "Payer LNbits root URL, blank to skip" "")")"
payer_lnbits_admin_key=""
if [ -n "$payer_lnbits_url" ]; then
  payer_lnbits_admin_key="$(prompt_secret "Payer LNbits admin key (input hidden)")"
fi

require_bool "PAYGATE_ENABLED" "$paygate_enabled"
require_non_empty "PAYGATE_BACKEND" "$paygate_backend"

if [ "$paygate_enabled" = "true" ] && [ "$paygate_backend" = "lnbits" ]; then
  require_non_empty "PAYGATE_LNBITS_URL" "$lnbits_url"
  require_non_empty "PAYGATE_LNBITS_API_KEY" "$lnbits_api_key"
  require_non_empty "PAYGATE_PROTOCOLS_MPP_CHALLENGE_BINDING_SECRET" "$binding_secret"
fi

require_non_empty "PAYGATE_BASE_URL" "$paygate_base_url"
require_non_empty "BASE_URL" "$base_url"
require_non_empty "REPORT_SIGNING_KEY_ID" "$report_signing_key_id"
require_non_negative_int "PAYGATE_LIGHTNING_TIMEOUT_SECONDS" "$lightning_timeout"
require_non_negative_int "PAYGATE_LNBITS_REQUEST_TIMEOUT_SECONDS" "$lnbits_request_timeout"
require_non_negative_int "PAYGATE_LNBITS_CONNECT_TIMEOUT_SECONDS" "$lnbits_connect_timeout"
require_hex_if_present "PAYER_LND_MACAROON_HEX" "$voltage_macaroon"

if [ -f "$ENV_PATH" ]; then
  backup="$ENV_PATH.bak-$(date +%Y%m%d%H%M%S)"
  cp "$ENV_PATH" "$backup"
  chmod 600 "$backup"
  printf 'Backed up existing %s to %s\n' "$ENV_PATH" "$backup"
fi

umask 077
{
  printf '# Local Paygate Agent Trust environment.\n'
  printf '# Generated by scripts/setup-local-env.sh. Do not commit this file.\n\n'
  printf 'PAYGATE_ENABLED=%s\n' "$(shell_quote "$paygate_enabled")"
  printf 'PAYGATE_BACKEND=%s\n' "$(shell_quote "$paygate_backend")"
  printf 'PAYGATE_LNBITS_URL=%s\n' "$(shell_quote "$lnbits_url")"
  printf 'PAYGATE_LNBITS_API_KEY=%s\n' "$(shell_quote "$lnbits_api_key")"
  printf 'PAYGATE_PROTOCOLS_MPP_CHALLENGE_BINDING_SECRET=%s\n' "$(shell_quote "$binding_secret")"
  printf 'PAYGATE_LIGHTNING_TIMEOUT_SECONDS=%s\n' "$(shell_quote "$lightning_timeout")"
  printf 'PAYGATE_LNBITS_REQUEST_TIMEOUT_SECONDS=%s\n' "$(shell_quote "$lnbits_request_timeout")"
  printf 'PAYGATE_LNBITS_CONNECT_TIMEOUT_SECONDS=%s\n\n' "$(shell_quote "$lnbits_connect_timeout")"
  printf 'PAYGATE_BASE_URL=%s\n' "$(shell_quote "$paygate_base_url")"
  printf 'BASE_URL=%s\n' "$(shell_quote "$base_url")"
  printf 'REPORT_SIGNING_KEY_ID=%s\n' "$(shell_quote "$report_signing_key_id")"

  if [ -n "$voltage_url" ]; then
    printf '\nPAYER_LND_REST_URL=%s\n' "$(shell_quote "$voltage_url")"
    printf 'PAYER_LND_MACAROON_HEX=%s\n' "$(shell_quote "$voltage_macaroon")"
    if [ -n "$voltage_tls_cert_path" ]; then
      printf 'PAYER_LND_TLS_CERT_PATH=%s\n' "$(shell_quote "$voltage_tls_cert_path")"
    fi
  fi

  if [ -n "$payer_lnbits_url" ]; then
    printf '\nPAYER_LNBITS_URL=%s\n' "$(shell_quote "$payer_lnbits_url")"
    printf 'PAYER_LNBITS_ADMIN_KEY=%s\n' "$(shell_quote "$payer_lnbits_admin_key")"
  fi
} > "$ENV_PATH"

chmod 600 "$ENV_PATH"

printf '\nWrote %s\n' "$ENV_PATH"
printf '\nNext commands:\n'
printf '  source scripts/local-dev-env.sh\n'
printf '  ./gradlew bootRun\n'
printf '\nIn another shell, run your Voltage paid test:\n'
printf '  source scripts/local-dev-env.sh\n'
printf '  scripts/paygate-lnd-real-sats-test.sh example.com dns\n'
