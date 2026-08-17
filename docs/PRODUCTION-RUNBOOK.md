# Production Runbook

## Architecture

Fly runs one always-on 1 GB shared-CPU Machine in `iad`; LNbits is the payee backend. Breez SDK Spark is only the external verification payer. The encrypted `paygate_keys` Fly volume persists macaroon root keys at `/home/app/.paygate`; there is no database. Rate limits and report caches are process-local, so horizontal scaling requires distributed replacements and root-key replication first.

## Provisioning

1. Confirm the Fly organization and app name, then run `flyctl launch --no-deploy --copy-config`.
2. Generate Ed25519 material offline, convert it to Base64 PKCS#8 private and X.509 public DER, choose a stable key ID such as `2026-07-prod`, and assign encrypted-backup ownership.
3. Generate a separate MPP binding secret of at least 32 bytes.
4. Create an app-scoped Fly token with an expiry, store it only as the protected GitHub `production` environment's `FLY_API_TOKEN`, and require environment approval before deployments. Keep workflow actions pinned to immutable commits.
5. Stage Fly secrets with `scripts/configure-fly-production-secrets.sh`. It reads generated key files when available, otherwise accepts hidden input restored from the encrypted recovery vault. It validates the HTTPS LNbits URL, binding-secret length, signing-key encodings, and key-pair match before using `flyctl secrets import --stage`; it never deploys. Never pass secret values as command-line arguments or retain them in shell history, source files, or CI logs. Use `flyctl secrets list` to inspect names only.
6. Confirm `fly.toml` declares the 1 GB `paygate_keys` volume in `iad`. The first deploy creates it; after deployment, confirm `flyctl volumes list -a paygate-agent-trust` shows it as encrypted and attached. Fly retains daily snapshots for 14 days.
7. Keep `PAYGATE_REQUEST_BODY_MAX_BYTES=8192` and `PAYGATE_RATE_LIMIT_IPV6_PREFIX_LENGTH=64` unless a reviewed workload or abuse-control change requires different bounds. The paid report is a bodyless `GET`, so the 8 KiB payment-processing limit is sufficient.
8. Production deploys only from strict `vX.Y.Z` tags already contained in `master`.

`/healthz` intentionally does not depend on LNbits. Validate LNbits through challenge creation and the paid smoke.

## Verification

Run `BASE_URL=https://paygate-agent-trust.fly.dev EXPECTED_KEY_ID=2026-07-prod scripts/production-smoke.sh`, inspect `flyctl checks list`, then run the Breez paid smoke from the restricted payer runner. Record invoice creation, preimage/hash verification, receipt, public report verification, tag, SHA, and Fly metadata.

## Rotation

Generate and back up replacements offline. Change private key, public key, and key ID together; startup rejects mismatches. Rotating LNbits or MPP secrets invalidates outstanding challenges. Deploy, rerun all smoke checks, retain the former public key and ID in the incident record, then revoke superseded credentials. Never store Breez credentials in Fly.

## Paygate 0.1.5 Migration

Preserve the existing encrypted `paygate_keys` volume and current MPP challenge-binding secret throughout rollout. Paygate 0.1.5 binds credentials to the exact method, registered route, raw-query presence/value, bounded body, and authenticated expiry. Existing 0.1.4 L402 and MPP credentials are intentionally rejected despite stable key material; warn clients before rollout that they may need to request a fresh challenge and pay again. Record post-deploy evidence for a `402`, an exact-query paid retry, its `Payment-Receipt`, and rejection of a changed raw query.

## Rollback

Redeploy the last verified immutable Fly image with the existing `paygate_keys` volume attached, wait for routing health, and rerun health, catalog/key ID, quote, and `402` checks. Run paid verification when payment behavior may be affected. If the volume is lost, restore its latest snapshot before serving paid traffic; rotating to a fresh root key invalidates outstanding L402 credentials. Treat rollback from Paygate 0.1.5 as a security exception requiring explicit review: an older image can accept legacy boundary-incomplete credentials that 0.1.5 rejects, and rejection by 0.1.5 does not make those credentials safe to accept again. Open a forward-fix patch; never move or reuse a tag. Rehearse this once before declaring the service operational.
