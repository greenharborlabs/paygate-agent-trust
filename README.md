# Paygate Agent Trust

Public Spring Boot reference service for selling signed agent trust reports through Paygate. The service exposes a free catalog and quote API, then protects trust report generation with Paygate payment challenges backed by LNbits in production.

## What It Does

- Validates bare domain names and rejects URLs, raw IP addresses, and malformed domains.
- Resolves DNS and rejects targets that resolve to private, loopback, link-local, multicast, or otherwise non-public addresses.
- Produces paid JSON trust reports for selected checks: `dns`, `tls`, `http`, and `robots`.
- Signs report payloads with Ed25519 and publishes the verification key in the catalog.
- Caches generated reports for a short TTL to avoid repeated network work for the same domain/check set.
- Uses bounded HTTP fetching: HTTPS only, no redirects, no cookies, capped headers/body, and configurable timeouts.

## Requirements

- Java 25
- Gradle wrapper from this repository
- LNbits credentials for production Paygate payments
- Ed25519 signing keys for report signatures

## Endpoints

| Method | Path | Protected | Description |
| --- | --- | --- | --- |
| `GET` | `/healthz` | No | Lightweight health check, returns `{"status":"ok"}`. |
| `GET` | `/api/v1/catalog` | No | Service metadata, supported checks, pricing, and report signature public key. |
| `GET` | `/api/v1/trust/quote?domain=example.com&checks=dns,tls,http,robots` | No | Quote for a domain/check set. |
| `GET` | `/api/v1/trust/report?domain=example.com&checks=dns,tls,http,robots` | Yes | Paid, signed trust report. |

The `checks` query parameter is optional. When omitted or blank, the service runs only `dns`. Supported values are comma-separated from `dns`, `tls`, `http`, and `robots`.

## Pricing

Quotes and payment challenges use dynamic pricing:

| Item | Price |
| --- | ---: |
| Base report / `dns` | 10 sats |
| `tls` | +5 sats |
| `http` | +10 sats |
| `robots` | +5 sats |
| Maximum price | 50 sats |

Example quote:

```bash
curl -s "http://localhost:8080/api/v1/trust/quote?domain=example.com&checks=dns,tls,http,robots"
```

```json
{
  "domain": "example.com",
  "priceSats": 30
}
```

## Configuration

For local development without real Lightning payments, source the helper script before starting the app:

```bash
source scripts/local-dev-env.sh
./gradlew bootRun
```

The script creates `report-signing-private.pem` if it does not already exist, exports the base64 DER signing keys, sets `REPORT_SIGNING_KEY_ID=local-dev`, and sets `PAYGATE_ENABLED=false`. With Paygate disabled, the protected report endpoint can be exercised locally without a payment challenge.

Required production configuration:

| Environment variable | Purpose |
| --- | --- |
| `PAYGATE_ENABLED=true` | Enables Paygate payment protection. |
| `PAYGATE_BACKEND=lnbits` | Selects the LNbits payment backend. |
| `PAYGATE_LNBITS_URL` | LNbits base URL, for example `https://legend.lnbits.com` or your self-hosted URL. |
| `PAYGATE_LNBITS_API_KEY` | LNbits wallet API key used by Paygate to create invoices and check payment status. |
| `PAYGATE_PROTOCOLS_MPP_CHALLENGE_BINDING_SECRET` | Secret used by the MPP payment protocol. Must be at least 32 UTF-8 bytes. |
| `REPORT_SIGNING_PRIVATE_KEY` | Base64 DER PKCS#8 Ed25519 private key. |
| `REPORT_SIGNING_PUBLIC_KEY` | Base64 DER X.509 Ed25519 public key. |
| `REPORT_SIGNING_KEY_ID` | Stable key identifier published in reports and catalog. |

LNbits setup:

1. Create or choose an LNbits wallet. For hosted experimentation, use an LNbits instance such as `https://legend.lnbits.com`; for self-hosting, start with the official LNbits docs: <https://docs.lnbits.com/>.
2. Open your LNbits wallet, go to its API/info section, and copy a wallet API key that can create invoices and read payment status. The Paygate starter sends it as the `X-Api-Key` header to LNbits.
3. Set `PAYGATE_LNBITS_URL` to the LNbits server root URL, without an API path.
4. Set `PAYGATE_LNBITS_API_KEY` to the wallet key.
5. Set a long random `PAYGATE_PROTOCOLS_MPP_CHALLENGE_BINDING_SECRET`. MPP is enabled automatically when this secret is present.

Every LNbits instance also exposes interactive API documentation at `/docs` and `/redoc`. For example, if your instance is `https://legend.lnbits.com`, check `https://legend.lnbits.com/docs`.

Verify the LNbits key before starting this app:

```bash
curl -s "$PAYGATE_LNBITS_URL/api/v1/wallet" \
  -H "X-Api-Key: $PAYGATE_LNBITS_API_KEY"
```

Create a test invoice directly through LNbits:

```bash
curl -s -X POST "$PAYGATE_LNBITS_URL/api/v1/payments" \
  -H "X-Api-Key: $PAYGATE_LNBITS_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"out": false, "amount": 10, "memo": "paygate local test"}'
```

Example LNbits-backed local run:

```bash
source scripts/local-dev-env.sh

export PAYGATE_ENABLED=true
export PAYGATE_BACKEND=lnbits
export PAYGATE_LNBITS_URL="https://legend.lnbits.com"
export PAYGATE_LNBITS_API_KEY="..."
export PAYGATE_PROTOCOLS_MPP_CHALLENGE_BINDING_SECRET="$(openssl rand -base64 32)"

./gradlew bootRun
```

With `PAYGATE_ENABLED=true`, `GET /api/v1/trust/report` returns `402 Payment Required` until the client pays a challenge and retries with a valid `Authorization` header.

Optional service tuning:

| Environment variable | Default | Purpose |
| --- | ---: | --- |
| `REFERENCE_DNS_TIMEOUT_SECONDS` | `1` | DNS timeout budget. |
| `REFERENCE_CONNECT_TIMEOUT_SECONDS` | `2` | Outbound HTTPS connect timeout. |
| `REFERENCE_READ_TIMEOUT_SECONDS` | `3` | Outbound HTTPS response timeout. |
| `REFERENCE_TOTAL_BUDGET_SECONDS` | `6` | Overall report budget setting. |
| `REFERENCE_MAX_BODY_BYTES` | `65536` | Maximum stored response body length. |
| `REFERENCE_MAX_HEADERS_BYTES` | `8192` | Maximum stored response header bytes. |
| `REFERENCE_MAX_HEADERS_COUNT` | `32` | Maximum stored response header count. |
| `REFERENCE_CACHE_TTL_MINUTES` | `15` | Report cache TTL. |
| `REFERENCE_MAX_CONCURRENT_CHECKS` | `16` | Concurrency limit setting. |

Generate signing keys:

```bash
openssl genpkey -algorithm ED25519 -out report-signing-private.pem
openssl pkcs8 -topk8 -nocrypt -in report-signing-private.pem -outform DER | base64
openssl pkey -in report-signing-private.pem -pubout -outform DER | base64
```

Use the second command output for `REPORT_SIGNING_PRIVATE_KEY` and the third command output for `REPORT_SIGNING_PUBLIC_KEY`.

## Run Locally

Start without real payment protection:

```bash
source scripts/local-dev-env.sh
./gradlew bootRun
```

Then test the app:

```bash
curl -s http://localhost:8080/healthz
curl -s "http://localhost:8080/api/v1/catalog"
curl -s "http://localhost:8080/api/v1/trust/quote?domain=example.com&checks=dns,tls,http,robots"
curl -i "http://localhost:8080/api/v1/trust/report?domain=example.com&checks=dns"
```

Because `scripts/local-dev-env.sh` sets `PAYGATE_ENABLED=false`, the report request should return `200 OK` without a payment.

To test the Paygate payment flow locally without LNbits, use Paygate test mode. This keeps payment protection enabled but uses an in-memory Lightning backend that auto-settles test invoices.

```bash
source scripts/local-dev-env.sh

export PAYGATE_ENABLED=true
export PAYGATE_TEST_MODE=true
export SPRING_PROFILES_ACTIVE=local
export PAYGATE_ROOT_KEY_STORE=memory
export PAYGATE_PROTOCOLS_MPP_CHALLENGE_BINDING_SECRET="local-dev-mpp-secret-at-least-32-bytes"

./gradlew bootRun
```

In another shell:

```bash
scripts/paygate-test-report.sh example.com dns
```

The helper requests the challenge, builds the local test credential from `test_preimage`, and retries with `Authorization: Payment ...`. Pass a comma-separated checks list as the second argument:

```bash
scripts/paygate-test-report.sh example.com dns,tls,http,robots
```

To run the same flow manually:

```bash
curl -s -o /tmp/paygate-challenge.json -w "%{http_code}\n" \
  "http://localhost:8080/api/v1/trust/report?domain=example.com&checks=dns"
```

The status should be `402`. Build a test MPP credential from the challenge body:

```bash
export PAYGATE_TEST_CREDENTIAL="$(
  python3 - <<'PY'
import base64
import json

with open("/tmp/paygate-challenge.json", "r", encoding="utf-8") as f:
    body = json.load(f)

credential = {
    "challenge": body["protocols"]["Payment"],
    "source": "local-dev",
    "payload": {"preimage": body["test_preimage"]},
}

encoded = base64.urlsafe_b64encode(json.dumps(credential, separators=(",", ":")).encode())
print(encoded.decode().rstrip("="))
PY
)"
```

Retry the paid endpoint:

```bash
curl -i \
  -H "Authorization: Payment $PAYGATE_TEST_CREDENTIAL" \
  "http://localhost:8080/api/v1/trust/report?domain=example.com&checks=dns"
```

The retry should return `200 OK` with a `Payment-Receipt` header.

To test against real LNbits locally, use the LNbits-backed env vars from the configuration section and leave `PAYGATE_TEST_MODE` unset. The first report request should return `402 Payment Required` and create a real LNbits invoice. A full paid retry requires a client that can pay the invoice and send back a valid `L402` or `Payment` authorization credential.

Run tests:

```bash
./gradlew test
```

## Docker

```bash
docker build -t paygate-agent-trust .
docker run --rm -p 8080:8080 \
  -e PAYGATE_BACKEND=lnbits \
  -e PAYGATE_LNBITS_URL=... \
  -e PAYGATE_LNBITS_API_KEY=... \
  -e PAYGATE_PROTOCOLS_MPP_CHALLENGE_BINDING_SECRET=... \
  -e REPORT_SIGNING_PRIVATE_KEY=... \
  -e REPORT_SIGNING_PUBLIC_KEY=... \
  -e REPORT_SIGNING_KEY_ID=2026-05-prod \
  paygate-agent-trust
```

## Fly.io

The included `fly.toml` deploys the Dockerfile, exposes port `8080`, and uses `/healthz` for health checks.

```bash
fly launch --copy-config --no-deploy
fly secrets set \
  PAYGATE_BACKEND=lnbits \
  PAYGATE_LNBITS_URL=... \
  PAYGATE_LNBITS_API_KEY=... \
  PAYGATE_PROTOCOLS_MPP_CHALLENGE_BINDING_SECRET=... \
  REPORT_SIGNING_PRIVATE_KEY=... \
  REPORT_SIGNING_PUBLIC_KEY=... \
  REPORT_SIGNING_KEY_ID=2026-05-prod
fly deploy
```

## Smoke Flow

```bash
curl -i "https://<app>.fly.dev/api/v1/trust/report?domain=example.com&checks=dns"
curl -s "https://<app>.fly.dev/api/v1/trust/quote?domain=example.com&checks=dns"
```

The first response should be `402 Payment Required` with `WWW-Authenticate` challenges for `L402` and `Payment`. After paying either challenge invoice, retry the report request with `Authorization` and expect `200 OK` plus a `Payment-Receipt` header for MPP.

## Report Shape

A successful report includes:

- `reportId`
- `reportDigest`
- `signature.algorithm`, `signature.keyId`, and `signature.value`
- `domain`
- `checkedAt`
- `checks`
- `verdict`
- `cache`

The signed payload is:

```json
{
  "domain": "example.com",
  "checkedAt": "2026-05-28T00:00:00Z",
  "checks": {}
}
```

Use the catalog `signature.publicKey` to verify `signature.value` over that payload with Ed25519.

## Error Responses

Application errors are returned as RFC 9457-style problem details with `code` and `retryable` fields. Common codes include:

- `INVALID_DOMAIN`
- `UNSUPPORTED_CHECK`
- `DNS_LOOKUP_FAILED`
- `UNSAFE_TARGET`
- `TARGET_TIMEOUT`
- `TARGET_TLS_FAILED`
- `TARGET_FETCH_FAILED`
- `REPORT_SIGNING_FAILED`
