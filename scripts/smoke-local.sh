#!/usr/bin/env sh
set -eu

BASE_URL="${PAYGATE_BASE_URL:-${BASE_URL:-http://localhost:8080}}"
DOMAIN="${1:-example.com}"
CHECKS="${2:-dns}"

if ! command -v curl >/dev/null 2>&1; then
  echo "curl is required to smoke test the local service." >&2
  exit 1
fi

REPORT_FILE="$(mktemp "${TMPDIR:-/tmp}/paygate-report.XXXXXX")"
VERIFY_FILE="$(mktemp "${TMPDIR:-/tmp}/paygate-verify.XXXXXX")"
trap 'rm -f "$REPORT_FILE" "$VERIFY_FILE"' EXIT INT TERM

request() {
  method="$1"
  path="$2"
  output="$3"
  shift 3

  status="$(
    curl -sS -o "$output" -w "%{http_code}" -X "$method" "$BASE_URL$path" "$@"
  )"

  case "$status" in
    2*) printf "ok   %s %s -> %s\n" "$method" "$path" "$status" ;;
    *)
      printf "fail %s %s -> %s\n" "$method" "$path" "$status" >&2
      printf "Response body:\n" >&2
      cat "$output" >&2
      printf "\n" >&2
      exit 1
      ;;
  esac
}

echo "Smoke testing $BASE_URL with domain=$DOMAIN checks=$CHECKS"

request GET "/healthz" /dev/null
request GET "/api/v1/catalog" /dev/null
request GET "/api/v1/verification/keys" /dev/null
request GET "/api/v1/trust/quote?domain=$DOMAIN&checks=$CHECKS" /dev/null
request GET "/api/v1/trust/report?domain=$DOMAIN&checks=$CHECKS" "$REPORT_FILE"
request POST "/api/v1/trust/verify" "$VERIFY_FILE" \
  -H "Content-Type: application/json" \
  --data-binary "@$REPORT_FILE"
request GET "/v3/api-docs" /dev/null
request GET "/v3/api-docs.yaml" /dev/null

if ! grep -q '"valid":true' "$VERIFY_FILE"; then
  echo "fail POST /api/v1/trust/verify returned 2xx but did not verify the generated report." >&2
  cat "$VERIFY_FILE" >&2
  printf "\n" >&2
  exit 1
fi

echo "Smoke test passed."
