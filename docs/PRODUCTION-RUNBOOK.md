# Production Runbook

## Architecture

Fly runs one always-on 1 GB shared-CPU Machine in `iad`; LNbits is the payee backend. Breez SDK Spark is only the external verification payer. There is no volume or database. Rate limits and report caches are process-local, so horizontal scaling requires distributed replacements first.

## Provisioning

1. Confirm the Fly organization and app name, then run `flyctl launch --no-deploy --copy-config`.
2. Generate Ed25519 material offline, convert it to Base64 PKCS#8 private and X.509 public DER, choose a stable key ID such as `2026-07-prod`, and assign encrypted-backup ownership.
3. Generate a separate MPP binding secret of at least 32 bytes.
4. Create an app-scoped Fly token with an expiry, store it only as the protected GitHub `production` environment's `FLY_API_TOKEN`, and require environment approval before deployments. Keep workflow actions pinned to immutable commits.
5. Stage Fly secrets with `scripts/configure-fly-production-secrets.sh`. It reads generated key files when available, otherwise accepts hidden input restored from the encrypted recovery vault. It validates the HTTPS LNbits URL, binding-secret length, signing-key encodings, and key-pair match before using `flyctl secrets import --stage`; it never deploys. Never pass secret values as command-line arguments or retain them in shell history, source files, or CI logs. Use `flyctl secrets list` to inspect names only.
6. Production deploys only from strict `vX.Y.Z` tags already contained in `master`.

`/healthz` intentionally does not depend on LNbits. Validate LNbits through challenge creation and the paid smoke.

## Verification

Run `BASE_URL=https://paygate-agent-trust.fly.dev EXPECTED_KEY_ID=2026-07-prod scripts/production-smoke.sh`, inspect `flyctl checks list`, then run the Breez paid smoke from the restricted payer runner. Record invoice creation, preimage/hash verification, receipt, public report verification, tag, SHA, and Fly metadata.

## Rotation

Generate and back up replacements offline. Change private key, public key, and key ID together; startup rejects mismatches. Rotating LNbits or MPP secrets invalidates outstanding challenges. Deploy, rerun all smoke checks, retain the former public key and ID in the incident record, then revoke superseded credentials. Never store Breez credentials in Fly.

## Rollback

Redeploy the last verified immutable Fly image, wait for routing health, and rerun health, catalog/key ID, quote, and `402` checks. Run paid verification when payment behavior may be affected. Open a forward-fix patch; never move or reuse a tag. Rehearse this once before declaring the service operational.
