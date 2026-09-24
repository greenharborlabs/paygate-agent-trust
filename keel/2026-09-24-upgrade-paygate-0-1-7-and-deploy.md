# Upgrade Paygate to 0.1.7 and deploy Agent Trust
Status: review
Base revision: 996381b04f4f52acec4bd815a7a5f4f28f1b1469
Review scope: build.gradle.kts, gradle.properties, CHANGELOG.md (implementation commit fc2dd73)

## Goal
Run Agent Trust on Paygate starter and LNbits 0.1.7 in production under a new immutable service release (expected v0.1.5), with passing local/CI, container, Fly, unauthenticated, and protected paid-flow verification; record tag, SHA, Fly image/release, and paid-smoke outcome. Do not claim deployment complete until protected release finalization succeeds.

## Constraints
- At base revision the service used Paygate 0.1.6, Boot 4.0.7, version 0.1.4 ([build.gradle.kts](../build.gradle.kts), [gradle.properties](../gradle.properties)). Upstream [0.1.7 changelog](https://github.com/greenharborlabs/spring-boot-starter-paygate/blob/v0.1.7/CHANGELOG.md) describes Boot 4.0.8/Tomcat 11.0.25 security updates. Both 0.1.7 POMs resolved on Maven Central; Boot 4.0.8's BOM still selects Tomcat 11.0.24, requiring an explicit 11.0.25 override.
- Preserve file-backed root keys, MPP secret, encrypted Fly volume and single-machine topology; `/healthz` does not test LNbits. Legacy credential rejection and rollback security risks remain ([docs/PRODUCTION-RUNBOOK.md](../docs/PRODUCTION-RUNBOOK.md)); upstream 0.1.7 migration statements largely refer to earlier hardening, so validate actual differences rather than promise a new migration.
- Follow reviewed merge-to-master, immutable annotated tag and protected paid-smoke handoff ([CLAUDE.md](../CLAUDE.md), [docs/RELEASE-CHECKLIST.md](../docs/RELEASE-CHECKLIST.md), [.github/workflows/release.yml](../.github/workflows/release.yml), [.github/workflows/finalize-release.yml](../.github/workflows/finalize-release.yml)). No secret values in repository or logs.
- Pre-existing untracked `package-lock.json` is unrelated; leave untouched.

## Decisions
- Upgrade both Paygate modules together to 0.1.7 and align the service's direct Boot plugin to 4.0.8 if compatible; do not merely bump the starter while leaving the old Boot BOM or expand into unrelated feature work. Inspect resolved Tomcat version and fail closed if release artifacts or compatibility are unavailable.
- Target service v0.1.5 (distinct from dependency 0.1.7) and use the existing release pipeline, not direct `fly deploy` or a reused tag. Preserve security-bound settings and assert current payment challenge, paid exact-query retry/receipt, changed-query rejection and production startup; extend focused tests only where 0.1.7 changes behavior.
- Update [CHANGELOG.md](../CHANGELOG.md) for the service release. Update [docs/PRODUCTION-RUNBOOK.md](../docs/PRODUCTION-RUNBOOK.md) only for verified new operational/migration behavior; do not copy upstream historical claims as fresh changes. Release evidence belongs in the protected workflow/GitHub Release, not in a new documentation file.

## Tasks
- [x] Confirm upstream 0.1.7 artifacts/release notes and resolved dependency graph; bump both Paygate coordinates and compatible Boot plugin, and override Tomcat to 11.0.25; verify compilation and local startup.
- [x] Run focused Paygate/config/payment-flow tests through `./gradlew clean quality --no-daemon`, `shellcheck scripts/*.sh`, container smoke on free port, and `flyctl config validate`.
- [ ] Review committed v0.1.5 version/changelog and merge a green reviewed release PR into master. No runbook correction or test change was warranted by the patch-only upstream 0.1.7 changelog and passing existing payment-flow checks.
- [ ] From merged master create/push immutable annotated `v0.1.5` tag; monitor tagged workflow, Fly status/checks and health/production smoke; stop on failure without moving the tag.
- [ ] Run restricted Breez paid smoke including invoice/preimage, receipt, signed report verification and exact-query behavior; finalize protected GitHub Release with SHA/Fly metadata and smoke evidence, then start next snapshot by normal PR per release checklist. Do not call a 402-only smoke a paid pass.

## Review
Awaiting fresh-context review of implementation commit fc2dd73 against base 996381b04f4f52acec4bd815a7a5f4f28f1b1469. Both Paygate 0.1.7 POMs returned 200 from Maven Central; no local Maven 0.1.7 artifacts. Gradle runtime graph selects Paygate 0.1.7 and Tomcat core/websocket 11.0.25. `./gradlew clean quality --no-daemon` (tests, PMD, SpotBugs), shellcheck, Fly config validation and local container paid-test-mode smoke passed. Initial container attempt failed because port 18080 was occupied; `PORT=18087 scripts/container-smoke.sh` passed. Live production, real sats, tag ancestry and protected CI gates are unverified pending reviewed merge/release. No deployment was attempted.

## Outcome
Implementation committed; release and deployment pending review.

## Next action
Fresh-context review, then reviewed release PR; only after approval and merge follow immutable tag, Fly and protected paid-smoke steps.
