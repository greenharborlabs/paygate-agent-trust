# Paygate Agent Trust

Public Spring Boot reference service for selling signed agent trust reports through Paygate. The service exposes a free catalog and quote API, then protects trust report generation with Paygate payment challenges backed by LNbits in production.

## What It Does

- Validates bare domain names and rejects URLs, raw IP addresses, and malformed domains.
- Resolves DNS and rejects targets that resolve to private, loopback, link-local, multicast, or otherwise non-public addresses.
- Produces paid JSON trust reports for selected checks: `dns`, `tls`, `http`, `redirects`, `robots`, `security_headers`, `content`, and `risk`.
- Runs a comprehensive default report when `checks` is omitted or blank.
- Inspects TLS certificate metadata, bounded redirect chains, robots/AI crawler policy, security headers, and bounded content signals such as login, paywall, and noindex markers.
- Adds deterministic risk scoring with evidence paths and explicit not-evaluated entries for checks that were not selected.
- Signs report payloads with Ed25519, publishes a JWKS-like verification key, and exposes a public report verification endpoint.
- Binds successful Paygate `Payment-Receipt` headers to the signed report when a paid request returns a receipt.
- Caches generated reports for a short TTL to avoid repeated network work for the same domain/check set.
- Uses bounded HTTP fetching: HTTPS only, manually vetted redirects for the redirect check, no cookies, capped headers/body, and configurable timeouts.
- Does not include live phishing, malware, reputation, domain age, registrar, WHOIS, or RDAP data in v1.

## Requirements

- Java 25
- Gradle wrapper from this repository
- LNbits credentials for production Paygate payments
- Ed25519 signing keys for report signatures

## Endpoints

Set a base URL first. For local development, use:

```bash
export BASE_URL="http://localhost:8080"
```

For a deployed Fly app, use:

```bash
export BASE_URL="https://<app>.fly.dev"
```

| Method | Path | Protected | Description |
| --- | --- | --- | --- |
| `GET` | `/healthz` | No | Lightweight health check, returns `{"status":"ok"}`. |
| `GET` | `/api/v1/catalog` | No | Service metadata, supported checks, default check set, pricing, verification URLs, and report signature public key. |
| `GET` | `/api/v1/verification/keys` | No | JWKS-like Ed25519 public key discovery for report verification. |
| `POST` | `/api/v1/trust/verify` | No | Verifies a report digest/signature and optional receipt binding. |
| `GET` | `/api/v1/trust/quote?domain=example.com&checks=dns,tls,http,redirects,robots,security_headers,content,risk` | No | Quote for a domain/check set. |
| `GET` | `/api/v1/trust/report?domain=example.com&checks=dns,tls,http,redirects,robots,security_headers,content,risk` | Yes | Paid, signed trust report. |

The `domain` query parameter must be a bare domain such as `example.com`. Do not send a URL, path, raw IP address, or protocol prefix. The `checks` query parameter is optional. When omitted or blank, the service runs the comprehensive default set: `dns`, `tls`, `http`, `redirects`, `robots`, `security_headers`, `content`, and `risk`. Explicit subsets still work, so `checks=dns` remains DNS-only.

## API Walkthrough

Start the app first. The simplest local mode disables real payment checks:

```bash
source scripts/local-dev-env.sh
./gradlew bootRun
```

Then set `BASE_URL` in a second shell and use the commands below:

```bash
export BASE_URL="http://localhost:8080"
```

### 1. Health Check

Use this to confirm the service is running.

```bash
curl -s "$BASE_URL/healthz"
```

Expected response:

```json
{"status":"ok"}
```

### 2. Catalog

Use this to discover supported checks, pricing, verification URLs, and the active Ed25519 public signing key.

```bash
curl -s "$BASE_URL/api/v1/catalog"
```

The response includes:

- `checks`: all supported check names.
- `defaultChecks`: the checks used when `checks` is omitted.
- `pricing`: base price, per-check add-ons, and the price cap.
- `verification.keysUrl`: where to fetch public verification keys.
- `verification.verifyUrl`: where to verify a signed report.
- `signature.publicKey`: the active public key in base64 DER form.

### 3. Public Verification Keys

Use this when an external verifier needs the report signing key.

```bash
curl -s "$BASE_URL/api/v1/verification/keys"
```

Expected shape:

```json
{
  "keys": [
    {
      "kty": "OKP",
      "crv": "Ed25519",
      "kid": "local-dev",
      "alg": "EdDSA",
      "use": "sig",
      "x": "..."
    }
  ]
}
```

### 4. Quote A Report

Use this before requesting a paid report. The quote endpoint is free.

Default comprehensive report:

```bash
curl -s --get "$BASE_URL/api/v1/trust/quote" \
  --data-urlencode "domain=example.com"
```

Expected response:

```json
{
  "domain": "example.com",
  "priceSats": 50
}
```

Cheaper DNS-only report:

```bash
curl -s --get "$BASE_URL/api/v1/trust/quote" \
  --data-urlencode "domain=example.com" \
  --data-urlencode "checks=dns"
```

Selected subset:

```bash
curl -s --get "$BASE_URL/api/v1/trust/quote" \
  --data-urlencode "domain=example.com" \
  --data-urlencode "checks=dns,tls,http,robots"
```

### 5. Generate A Signed Trust Report

Use this to generate the signed JSON report. In local no-payment mode, this returns `200 OK` directly:

```bash
curl -i --get "$BASE_URL/api/v1/trust/report" \
  --data-urlencode "domain=example.com" \
  --data-urlencode "checks=dns"
```

Save a report to a file for later verification:

```bash
curl -s --get "$BASE_URL/api/v1/trust/report" \
  --data-urlencode "domain=example.com" \
  --data-urlencode "checks=dns,tls,http,redirects,robots,security_headers,content,risk" \
  -o report.json
```

A successful report includes:

- `reportId`: server-generated report id.
- `reportDigest`: SHA-256 digest of the signed payload.
- `signature`: Ed25519 signature metadata and value.
- `domain`: normalized domain.
- `checkedAt`: report timestamp.
- `checks`: selected check results.
- `risk`: risk score when `risk` is selected.
- `verdict`: overall `ok` or `warn` status plus warnings.
- `cache`: whether the response came from the short-lived report cache.
- `receiptBinding`: present only when a paid MPP response includes a `Payment-Receipt` header.

### 6. Verify A Report

Use this to verify a report previously returned by `/api/v1/trust/report`.

```bash
curl -s -X POST "$BASE_URL/api/v1/trust/verify" \
  -H "Content-Type: application/json" \
  --data-binary @report.json
```

Expected successful response shape:

```json
{
  "valid": true,
  "reportDigest": "sha256:...",
  "keyId": "local-dev",
  "signatureValid": true,
  "digestMatches": true
}
```

If the report includes `receiptBinding`, the response also includes `receiptBindingValid`.

### 7. Call The Paid Endpoint With Paygate Test Mode

Use this flow when you want payment protection enabled locally without real LNbits invoices. Start the app with test mode:

```bash
source scripts/local-dev-env.sh

export PAYGATE_ENABLED=true
export PAYGATE_TEST_MODE=true
export SPRING_PROFILES_ACTIVE=local
export PAYGATE_ROOT_KEY_STORE=memory
export PAYGATE_PROTOCOLS_MPP_CHALLENGE_BINDING_SECRET="local-dev-mpp-secret-at-least-32-bytes"

./gradlew bootRun
```

In another shell, run the helper:

```bash
export BASE_URL="http://localhost:8080"
export PAYGATE_BASE_URL="$BASE_URL"
scripts/paygate-test-report.sh example.com dns
```

The helper does three things:

1. Calls `/api/v1/trust/report` and expects `402 Payment Required`.
2. Builds a local `Authorization: Payment ...` credential from the test challenge.
3. Retries the report request and prints the `200 OK` response with a `Payment-Receipt` header.

Pass a comma-separated check list as the second argument:

```bash
scripts/paygate-test-report.sh example.com dns,tls,http,redirects,robots,security_headers,content,risk
```

### 8. Call The Paid Endpoint With LNbits

Use this flow for a real payment-backed run.

```bash
source scripts/local-dev-env.sh

export PAYGATE_ENABLED=true
export PAYGATE_BACKEND=lnbits
export PAYGATE_LNBITS_URL="https://legend.lnbits.com"
export PAYGATE_LNBITS_API_KEY="..."
export PAYGATE_PROTOCOLS_MPP_CHALLENGE_BINDING_SECRET="$(openssl rand -base64 32)"

./gradlew bootRun
```

First request a report without authorization:

```bash
curl -i --get "$BASE_URL/api/v1/trust/report" \
  --data-urlencode "domain=example.com" \
  --data-urlencode "checks=dns"
```

Expected result: `402 Payment Required` with `WWW-Authenticate` challenges. Pay the invoice using an L402 or MPP-capable client, then retry the same URL with the returned authorization credential:

```bash
curl -i --get "$BASE_URL/api/v1/trust/report" \
  -H "Authorization: Payment <credential>" \
  --data-urlencode "domain=example.com" \
  --data-urlencode "checks=dns"
```

Expected result: `200 OK`. For MPP payments, the response includes a `Payment-Receipt` header and the report body includes `receiptBinding`.

## Checks And Features

Use the `checks` query parameter to choose which checks run. Multiple checks are comma-separated.

| Check | Example | What It Does |
| --- | --- | --- |
| `dns` | `checks=dns` | Resolves the domain and rejects unsafe private, loopback, link-local, multicast, or otherwise non-public targets. |
| `tls` | `checks=tls` | Inspects HTTPS certificate metadata and reports TLS warnings. |
| `http` | `checks=http` | Fetches `https://<domain>/` and returns status code plus bounded headers. Redirects are not followed by this check. |
| `redirects` | `checks=redirects` | Analyzes a bounded HTTPS redirect chain with DNS vetting at each hop. |
| `robots` | `checks=robots` | Fetches `/robots.txt` and `/ai.txt`, then reports crawler and AI policy signals. |
| `security_headers` | `checks=security_headers` | Checks HSTS, CSP, frame protection, referrer policy, permissions policy, and `X-Content-Type-Options`. |
| `content` | `checks=content` | Scans a capped response body for bounded signals such as login, paywall, and noindex markers. |
| `risk` | `checks=risk` | Computes a deterministic risk score from selected check evidence and lists checks/providers that were not evaluated. |

Common check combinations:

```bash
# DNS only, lowest cost.
curl -s --get "$BASE_URL/api/v1/trust/report" \
  --data-urlencode "domain=example.com" \
  --data-urlencode "checks=dns"

# Web posture without risk scoring.
curl -s --get "$BASE_URL/api/v1/trust/report" \
  --data-urlencode "domain=example.com" \
  --data-urlencode "checks=dns,tls,http,redirects,robots,security_headers,content"

# Full default report. Equivalent to omitting checks.
curl -s --get "$BASE_URL/api/v1/trust/report" \
  --data-urlencode "domain=example.com" \
  --data-urlencode "checks=dns,tls,http,redirects,robots,security_headers,content,risk"
```

Invalid input returns problem details:

```bash
curl -i --get "$BASE_URL/api/v1/trust/quote" \
  --data-urlencode "domain=https://example.com" \
  --data-urlencode "checks=dns"
```

Example error fields:

```json
{
  "title": "INVALID DOMAIN",
  "status": 400,
  "detail": "...",
  "code": "INVALID_DOMAIN",
  "retryable": false
}
```

## Pricing

Quotes and payment challenges use dynamic pricing:

| Item | Price |
| --- | ---: |
| Base report / `dns` | 10 sats |
| `tls` | +5 sats |
| `http` | +10 sats |
| `redirects` | +5 sats |
| `robots` | +5 sats |
| `security_headers` | +5 sats |
| `content` | +5 sats |
| `risk` | +5 sats |
| Maximum price | 50 sats |

Example quote:

```bash
curl -s --get "$BASE_URL/api/v1/trust/quote" \
  --data-urlencode "domain=example.com"
```

```json
{
  "domain": "example.com",
  "priceSats": 50
}
```

Explicit subsets are priced only for the selected checks:

```bash
curl -s --get "$BASE_URL/api/v1/trust/quote" \
  --data-urlencode "domain=example.com" \
  --data-urlencode "checks=dns,tls,http,robots"
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

Start without real payment protection, then follow the endpoint examples in the API walkthrough:

```bash
source scripts/local-dev-env.sh
./gradlew bootRun
```

Then test the app:

```bash
curl -s http://localhost:8080/healthz
curl -s "http://localhost:8080/api/v1/catalog"
curl -s "http://localhost:8080/api/v1/verification/keys"
curl -s "http://localhost:8080/api/v1/trust/quote?domain=example.com"
curl -i "http://localhost:8080/api/v1/trust/report?domain=example.com&checks=dns"
```

Because `scripts/local-dev-env.sh` sets `PAYGATE_ENABLED=false`, the report request should return `200 OK` without a payment.

To test the Paygate payment flow locally without LNbits, use Paygate test mode:

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
export PAYGATE_BASE_URL="http://localhost:8080"
scripts/paygate-test-report.sh example.com dns
```

The helper requests the challenge, builds the local test credential from `test_preimage`, and retries with `Authorization: Payment ...`. Pass a comma-separated checks list as the second argument.

```bash
scripts/paygate-test-report.sh example.com dns,tls,http,redirects,robots,security_headers,content,risk
```

To run the same flow manually, request a challenge:

```bash
curl -s -o /tmp/paygate-challenge.json -w "%{http_code}\n" --get "http://localhost:8080/api/v1/trust/report" \
  --data-urlencode "domain=example.com" \
  --data-urlencode "checks=dns"
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
curl -i --get "http://localhost:8080/api/v1/trust/report" \
  -H "Authorization: Payment $PAYGATE_TEST_CREDENTIAL" \
  --data-urlencode "domain=example.com" \
  --data-urlencode "checks=dns"
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
  -e PAYGATE_ENABLED=true \
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
  PAYGATE_ENABLED=true \
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
curl -i "https://<app>.fly.dev/api/v1/trust/report?domain=example.com"
curl -s "https://<app>.fly.dev/api/v1/trust/quote?domain=example.com"
curl -s "https://<app>.fly.dev/api/v1/verification/keys"
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
- `risk`, when the `risk` check is selected
- `verdict`
- `cache`
- optional `receiptBinding`

The signed payload is:

```json
{
  "domain": "example.com",
  "checkedAt": "2026-05-28T00:00:00Z",
  "checks": {},
  "risk": {
    "score": 0,
    "level": "low",
    "explanations": [],
    "notEvaluated": []
  },
  "verdict": {
    "status": "ok",
    "warnings": []
  }
}
```

Use the catalog `signature.publicKey`, or the public key from `/api/v1/verification/keys`, to verify `signature.value` over that payload with Ed25519. The public verifier can do this for you:

```bash
curl -s -X POST "http://localhost:8080/api/v1/trust/verify" \
  -H "Content-Type: application/json" \
  --data-binary @report.json
```

Successful verification returns fields including `valid`, `reportDigest`, `keyId`, `signatureValid`, `digestMatches`, and `receiptBindingValid` when the report includes a receipt binding.

When a paid MPP request returns a `Payment-Receipt` response header, the report body includes `receiptBinding` with:

- `receipt`
- `reportDigest`
- `reportSignature`
- `bindingDigest`
- `signature.algorithm`, `signature.keyId`, and `signature.value`

The receipt binding is signed separately so the report signature can remain stable over the canonical report payload.

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
