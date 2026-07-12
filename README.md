# Paygate Agent Trust

Public Spring Boot reference service for selling signed agent trust reports through Paygate. The service exposes free catalog, quote, key-discovery, and verification APIs, then protects trust report generation with Paygate payment challenges when payment enforcement is enabled.

## Contents

- [Choose A Run Mode](#choose-a-run-mode)
- [Quick Start: Local No-Payment Mode](#quick-start-local-no-payment-mode)
- [Run Modes](#run-modes)
- [API Walkthrough](#api-walkthrough)
- [Checks And Pricing](#checks-and-pricing)
- [Configuration Reference](#configuration-reference)
- [Docker](#docker)
- [Fly.io](#flyio)
- [Report Shape](#report-shape)
- [Error Responses](#error-responses)

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

The v1 reference service does not include live phishing, malware, reputation, domain age, registrar, WHOIS, or RDAP data.

## Requirements

- Java 25
- Gradle wrapper from this repository
- `openssl` for local report-signing key generation
- LNbits credentials only when testing or deploying real Lightning payments
- Ed25519 signing keys for any environment that returns signed reports

## Choose A Run Mode

Use this table first. Each mode below has a focused setup section with only the configuration it needs.

| Need | Mode | Payment behavior | Start here |
| --- | --- | --- | --- |
| Develop the API or run smoke tests without Lightning. | Local no-payment | `GET /api/v1/trust/report` returns `200 OK` directly. | [Local no-payment mode](#local-no-payment-mode) |
| Test Paygate challenge and retry locally without LNbits. | Local Paygate test mode | First request returns `402`; helper builds a local test credential and retries. | [Local Paygate test mode](#local-paygate-test-mode) |
| Confirm this service can create real LNbits invoices. | Local LNbits payee mode | First request returns `402` backed by a real LNbits invoice. | [Local LNbits payee mode](#local-lnbits-payee-mode) |
| Exercise the full agent/client payment loop with real sats. | Real-sats programmable payer | Helper receives `402`, pays invoice, builds `Authorization`, and retries. | [Real-sats programmable payer](#real-sats-programmable-payer) |
| Build or run the container locally. | Docker | Same env vars as the chosen payment mode. | [Docker](#docker) |
| Deploy the public reference service. | Fly.io | Real LNbits payment enforcement. | [Fly.io](#flyio) |

## Quick Start: Local No-Payment Mode

Use this for normal development. It generates local signing keys, disables payment enforcement, starts the service, and runs a smoke test.

```bash
source scripts/local-dev-env.sh
./gradlew bootRun
```

In another shell:

```bash
export BASE_URL="http://localhost:8080"
scripts/smoke-local.sh
```

`scripts/local-dev-env.sh` loads `.env` if present, creates `report-signing-private.pem` if needed, exports `REPORT_SIGNING_PRIVATE_KEY` and `REPORT_SIGNING_PUBLIC_KEY`, sets `REPORT_SIGNING_KEY_ID=local-dev`, and defaults `PAYGATE_ENABLED=false`.

## Run Modes

### Local No-Payment Mode

Use this when working on the API, checks, signing, verification, OpenAPI docs, or rate limiting without testing Paygate payment credentials.

```bash
source scripts/local-dev-env.sh
./gradlew bootRun
```

Expected protected endpoint behavior:

```bash
curl -i --get "http://localhost:8080/api/v1/trust/report" \
  --data-urlencode "domain=example.com" \
  --data-urlencode "checks=dns"
```

Expected result: `200 OK` with a signed report. No `Authorization` header is required because `PAYGATE_ENABLED=false`.

Run the local smoke suite:

```bash
scripts/smoke-local.sh
```

The smoke script calls health, catalog, verification keys, quote, report generation, report verification, and OpenAPI endpoints.

### Local Paygate Test Mode

Use this when you need payment protection behavior locally but do not want real LNbits invoices or real sats.

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

The helper:

1. Calls `/api/v1/trust/report` and expects `402 Payment Required`.
2. Builds a local `Authorization: Payment ...` credential from the test challenge.
3. Retries the report request and prints the `200 OK` response with a `Payment-Receipt` header.

Pass a comma-separated checks list as the second argument:

```bash
scripts/paygate-test-report.sh example.com dns,tls,http,redirects,robots,security_headers,content,risk
```

### Local LNbits Payee Mode

Use this to verify Paygate can create real LNbits invoices from this service. This mode does not require a programmable payer wallet unless you want to complete the paid retry.

Create or choose an LNbits payee wallet first. For hosted experimentation, use an LNbits instance such as `https://legend.lnbits.com`; for self-hosting, use the official LNbits docs: <https://docs.lnbits.com/>.

```bash
source scripts/local-dev-env.sh

export PAYGATE_ENABLED=true
export PAYGATE_BACKEND=lnbits
export PAYGATE_LNBITS_URL="https://<lnbits-instance>"
export PAYGATE_LNBITS_API_KEY="<payee-wallet-api-key>"
export PAYGATE_PROTOCOLS_MPP_CHALLENGE_BINDING_SECRET="$(openssl rand -base64 32)"

./gradlew bootRun
```

Verify the LNbits key if needed:

```bash
curl -s "$PAYGATE_LNBITS_URL/api/v1/wallet" \
  -H "X-Api-Key: $PAYGATE_LNBITS_API_KEY"
```

Request a paid report without authorization:

```bash
curl -i --get "http://localhost:8080/api/v1/trust/report" \
  --data-urlencode "domain=example.com" \
  --data-urlencode "checks=dns"
```

Expected result: `402 Payment Required` with `WWW-Authenticate` challenges and a real LNbits invoice. For a complete paid retry, use a programmable payer flow.

For repeated local testing, copy `.env.example` to `.env`, fill the LNbits values, and run:

```bash
source scripts/local-dev-env.sh
./gradlew bootRun
```

`.env` is ignored by git. Keep real LNbits keys only in `.env` or deploy secrets.

### Real-Sats Programmable Payer

Use this when you want to test the same loop an agent/client needs to perform: receive `402`, pay the invoice programmatically, extract the payment preimage, build `Authorization: Payment ...`, and retry the request.

Keep the app running in [local LNbits payee mode](#local-lnbits-payee-mode). LNbits remains the service-side payee; payer credentials stay client-side and must never be stored as Fly secrets.

#### LNbits Payer Wallet

Create two LNbits wallets:

- Payee wallet: used by this API through `PAYGATE_LNBITS_API_KEY`; it receives report payments.
- Payer wallet: used by the client/agent test; it sends sats and must use an LNbits admin key.

Fund the payer wallet with a small amount, such as 100-500 sats. Phone wallets such as Muun are useful for funding the payer wallet, but they are not the right tool for the programmable retry because they usually do not expose an API for the client to retrieve the preimage.

```bash
export PAYGATE_BASE_URL="http://localhost:8080"
export PAYER_LNBITS_URL="https://<lnbits-instance>"
export PAYER_LNBITS_ADMIN_KEY="<payer-wallet-admin-key>"
```

Confirm the payer wallet has funds:

```bash
curl -s "$PAYER_LNBITS_URL/api/v1/wallet" \
  -H "X-Api-Key: $PAYER_LNBITS_ADMIN_KEY"
```

Run the paid request:

```bash
scripts/paygate-lnbits-real-sats-test.sh example.com dns
```

The payer wallet must expose the payment preimage after sending the payment. LNbits normally does this through the payment lookup response.

#### Breez SDK Spark Payer

Install the pinned client with Breez support and use a funded mainnet wallet:

```bash
python -m pip install "paygate-client[breez] @ git+https://github.com/greenharborlabs/paygate-client.git@684b6f556d0916643e2283ef66b8c21d776de6bd"
export BREEZ_API_KEY="<breez-api-key>"
export BREEZ_MNEMONIC="<breez-wallet-seed-words>"
export PAYGATE_BASE_URL="http://localhost:8080"
scripts/paygate-breez-real-sats-test.sh example.com dns
```

The policy caps reports at 50 sats and fees at 10 sats. The Breez backend forces BOLT11 settlement with `prefer_spark=false`, requires a preimage, and verifies `sha256(preimage) == payment_hash` before retrying. The helper also requires a receipt and verifies the signed report publicly.

## API Walkthrough

Set a base URL first:

```bash
export BASE_URL="http://localhost:8080"
```

For a deployed app:

```bash
export BASE_URL="https://<app>.fly.dev"
```

### Endpoints

| Method | Path | Protected | Description |
| --- | --- | --- | --- |
| `GET` | `/healthz` | No | Lightweight health check, returns `{"status":"ok"}`. |
| `GET` | `/api/v1/catalog` | No | Service metadata, supported checks, default check set, pricing, verification URLs, and report signature public key. |
| `GET` | `/api/v1/verification/keys` | No | JWKS-like Ed25519 public key discovery for report verification. |
| `POST` | `/api/v1/trust/verify` | No | Verifies a report digest/signature and optional receipt binding. |
| `GET` | `/api/v1/trust/quote?domain=example.com&checks=dns,tls,http,redirects,robots,security_headers,content,risk` | No | Quote for a domain/check set. |
| `GET` | `/api/v1/trust/report?domain=example.com&checks=dns,tls,http,redirects,robots,security_headers,content,risk` | Yes | Signed trust report. Protected when `PAYGATE_ENABLED=true`. |

The `domain` query parameter must be a bare domain such as `example.com`. Do not send a URL, path, raw IP address, or protocol prefix.

The `checks` query parameter is optional. When omitted or blank, the service runs the comprehensive default set: `dns`, `tls`, `http`, `redirects`, `robots`, `security_headers`, `content`, and `risk`.

Public `/api/**` routes are rate limited per client IP. `/healthz` is intentionally not rate limited so platform health checks keep working.

### Health Check

```bash
curl -s "$BASE_URL/healthz"
```

Expected response:

```json
{"status":"ok"}
```

### Catalog

```bash
curl -s "$BASE_URL/api/v1/catalog"
```

The response includes supported checks, default checks, pricing, verification URLs, and the active Ed25519 public signing key.

### Verification Keys

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

### Quote A Report

Default comprehensive report:

```bash
curl -s --get "$BASE_URL/api/v1/trust/quote" \
  --data-urlencode "domain=example.com"
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

### Generate A Signed Trust Report

In local no-payment mode, this returns `200 OK` directly:

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

With `PAYGATE_ENABLED=true`, the first report request returns `402 Payment Required` until the client pays a challenge and retries with a valid `Authorization` header:

```bash
curl -i --get "$BASE_URL/api/v1/trust/report" \
  -H "Authorization: Payment <credential>" \
  --data-urlencode "domain=example.com" \
  --data-urlencode "checks=dns"
```

Expected paid retry result: `200 OK`. For MPP payments, the response includes a `Payment-Receipt` header and the report body includes `receiptBinding`.

### Verify A Report

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

### OpenAPI And Swagger UI

```bash
curl -s "$BASE_URL/v3/api-docs"
curl -s "$BASE_URL/v3/api-docs.yaml"
open "$BASE_URL/swagger-ui.html"
```

SwaggerHub/API Hub is not required for v1. The in-app Swagger UI is free to run with the service and keeps the published docs tied to the deployed API version.

## Checks And Pricing

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

Pricing:

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

## Configuration Reference

### Payment Configuration

| Environment variable | Required when | Purpose |
| --- | --- | --- |
| `PAYGATE_ENABLED` | Always useful | Enables Paygate payment protection when `true`. Defaults to `true` in `application.yml`, but `scripts/local-dev-env.sh` defaults it to `false`. |
| `PAYGATE_TEST_MODE` | Local test mode | Enables local test challenges. |
| `PAYGATE_ROOT_KEY_STORE` | Local test mode | Use `memory` for local test-mode challenge keys. |
| `SPRING_PROFILES_ACTIVE` | Local test mode | Use `local` for local test-mode runs. |
| `PAYGATE_BACKEND` | LNbits modes | Selects the Lightning backend. Use `lnbits`. |
| `PAYGATE_LNBITS_URL` | LNbits modes | LNbits base URL, for example `https://legend.lnbits.com` or your self-hosted URL. Do not include an API path. |
| `PAYGATE_LNBITS_API_KEY` | LNbits modes | Payee wallet API key used by Paygate to create invoices and check payment status. |
| `PAYGATE_PROTOCOLS_MPP_CHALLENGE_BINDING_SECRET` | Payment enabled | Secret used by the MPP payment protocol. Must be at least 32 UTF-8 bytes. |
| `PAYGATE_LIGHTNING_TIMEOUT_SECONDS` | Optional | Timeout budget for Lightning calls. |
| `PAYGATE_LNBITS_REQUEST_TIMEOUT_SECONDS` | Optional | LNbits request timeout. |
| `PAYGATE_LNBITS_CONNECT_TIMEOUT_SECONDS` | Optional | LNbits connect timeout. |

LNbits setup checklist:

1. Create or choose a dedicated payee wallet for this service.
2. Copy a wallet API key that can create invoices and read payment status.
3. Set `PAYGATE_LNBITS_URL` to the LNbits server root URL.
4. Set `PAYGATE_LNBITS_API_KEY` to the payee wallet key.
5. Generate and set a stable `PAYGATE_PROTOCOLS_MPP_CHALLENGE_BINDING_SECRET`.

Every LNbits instance also exposes interactive API documentation at `/docs` and `/redoc`.

### Report Signing Configuration

| Environment variable | Purpose |
| --- | --- |
| `REPORT_SIGNING_PRIVATE_KEY` | Base64 DER PKCS#8 Ed25519 private key. |
| `REPORT_SIGNING_PUBLIC_KEY` | Base64 DER X.509 Ed25519 public key. |
| `REPORT_SIGNING_KEY_ID` | Stable key identifier published in reports and catalog. |
| `REPORT_SIGNING_KEY_FILE` | Optional local private key path used by `scripts/local-dev-env.sh`. Defaults to `report-signing-private.pem`. |

Generate signing keys manually:

```bash
openssl genpkey -algorithm ED25519 -out report-signing-private.pem
openssl pkcs8 -topk8 -nocrypt -in report-signing-private.pem -outform DER | base64
openssl pkey -in report-signing-private.pem -pubout -outform DER | base64
```

Use the second command output for `REPORT_SIGNING_PRIVATE_KEY` and the third command output for `REPORT_SIGNING_PUBLIC_KEY`.

Production guardrails:

- Use a dedicated LNbits wallet for this service, not a shared admin wallet.
- Store the LNbits API key only as a deploy secret.
- Generate production Ed25519 report signing keys separately from local development keys.
- Record `REPORT_SIGNING_KEY_ID` as an operational identifier, for example `2026-06-prod`, and only change it during intentional signing key rotation.
- Keep `PAYGATE_ENABLED=false` for local no-payment smoke tests; set it to `true` only for Paygate test mode, LNbits-backed local runs, and production.
- Leave rate limiting enabled for public deploys. The in-app limiter is per-machine, so use one Fly machine for strict global limits or add Redis/edge limits before scaling horizontally.

### Service Tuning

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
| `REFERENCE_RATE_LIMIT_ENABLED` | `true` | Enables in-app public API rate limiting. |
| `REFERENCE_RATE_LIMIT_CATALOG_PER_MINUTE` | `120` | Per-client limit for `/api/v1/catalog`. |
| `REFERENCE_RATE_LIMIT_KEYS_PER_MINUTE` | `120` | Per-client limit for `/api/v1/verification/keys`. |
| `REFERENCE_RATE_LIMIT_QUOTE_PER_MINUTE` | `60` | Per-client limit for `/api/v1/trust/quote` and unknown `/api/**` routes. |
| `REFERENCE_RATE_LIMIT_VERIFY_PER_MINUTE` | `30` | Per-client limit for `/api/v1/trust/verify`. |
| `REFERENCE_RATE_LIMIT_REPORT_PER_MINUTE` | `10` | Per-client limit for `/api/v1/trust/report`. |
| `REFERENCE_RATE_LIMIT_BUCKET_TTL_MINUTES` | `30` | Idle bucket eviction window for per-client limiter state. |

## Docker

Build:

```bash
docker build -t paygate-agent-trust .
```

Run with no-payment local configuration:

```bash
source scripts/local-dev-env.sh

docker run --rm -p 8080:8080 \
  -e PAYGATE_ENABLED=false \
  -e REPORT_SIGNING_PRIVATE_KEY="$REPORT_SIGNING_PRIVATE_KEY" \
  -e REPORT_SIGNING_PUBLIC_KEY="$REPORT_SIGNING_PUBLIC_KEY" \
  -e REPORT_SIGNING_KEY_ID="$REPORT_SIGNING_KEY_ID" \
  paygate-agent-trust
```

Run with LNbits payment enforcement:

```bash
docker run --rm -p 8080:8080 \
  -e PAYGATE_ENABLED=true \
  -e PAYGATE_BACKEND=lnbits \
  -e PAYGATE_LNBITS_URL="https://<lnbits-instance>" \
  -e PAYGATE_LNBITS_API_KEY="<payee-wallet-api-key>" \
  -e PAYGATE_PROTOCOLS_MPP_CHALLENGE_BINDING_SECRET="<random-secret-at-least-32-bytes>" \
  -e REPORT_SIGNING_PRIVATE_KEY="<base64-der-private-key>" \
  -e REPORT_SIGNING_PUBLIC_KEY="<base64-der-public-key>" \
  -e REPORT_SIGNING_KEY_ID="2026-06-prod" \
  paygate-agent-trust
```

## Fly.io

The included `fly.toml` deploys the Dockerfile, exposes port `8080`, and uses `/healthz` for health checks.

```bash
fly launch --copy-config --no-deploy
fly secrets set \
  SPRING_PROFILES_ACTIVE=prod \
  PAYGATE_ENABLED=true \
  PAYGATE_BACKEND=lnbits \
  PAYGATE_LNBITS_URL="https://<lnbits-instance>" \
  PAYGATE_LNBITS_API_KEY="<payee-wallet-api-key>" \
  PAYGATE_PROTOCOLS_MPP_CHALLENGE_BINDING_SECRET="<random-secret-at-least-32-bytes>" \
  REPORT_SIGNING_PRIVATE_KEY="<base64-der-private-key>" \
  REPORT_SIGNING_PUBLIC_KEY="<base64-der-public-key>" \
  REPORT_SIGNING_KEY_ID="2026-06-prod"
fly deploy --remote-only
```

Before `fly deploy`, verify the exact secret names are present:

```bash
fly secrets list
```

After deploy, check health, catalog, and keys:

```bash
export BASE_URL="https://<app>.fly.dev"
curl -s "$BASE_URL/healthz"
curl -s "$BASE_URL/api/v1/catalog"
curl -s "$BASE_URL/api/v1/verification/keys"
```

Smoke the paid endpoint:

```bash
curl -i "$BASE_URL/api/v1/trust/report?domain=example.com"
curl -s "$BASE_URL/api/v1/trust/quote?domain=example.com"
```

The report response should be `402 Payment Required` with `WWW-Authenticate` challenges for `L402` and `Payment`. Run the Breez helper from a separately controlled payer runner to verify the paid retry, receipt, and signed report. Production intentionally remains one Machine because rate limits and caches are process-local; distributed semantics are required before horizontal scaling. See `docs/PRODUCTION-RUNBOOK.md` and `docs/RELEASE-CHECKLIST.md`.

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
curl -s -X POST "$BASE_URL/api/v1/trust/verify" \
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

Application errors are returned as RFC 9457-style problem details with `code` and `retryable` fields.

Invalid input example:

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

Common codes include:

- `INVALID_DOMAIN`
- `UNSUPPORTED_CHECK`
- `DNS_LOOKUP_FAILED`
- `UNSAFE_TARGET`
- `TARGET_TIMEOUT`
- `TARGET_TLS_FAILED`
- `TARGET_FETCH_FAILED`
- `REPORT_SIGNING_FAILED`

## Development

Run tests:

```bash
./gradlew test
```
