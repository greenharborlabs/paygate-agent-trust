# Upgrade Paygate to 0.1.7 and deploy Agent Trust
Status: closed
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

### Attempt 1
Reviewed revision: 7f14369713966c3eb5bd3413815532f777a45c9c
Verdict: pass
Findings and dispositions: No findings; no rework required. Scoped files (build.gradle.kts, gradle.properties, CHANGELOG.md) are clean; the only post-implementation commit (7f14369) edits this task record and does not invalidate the reviewed implementation. Verified both Paygate modules resolve to 0.1.7, Spring Boot BOM/plugin resolve to 4.0.8, and the dependency-management override lifts tomcat-embed-core/el/websocket from the BOM's 11.0.24 to 11.0.25, matching the record's constraint. Service version 0.1.5 in gradle.properties and CHANGELOG.md is consistent; no secret values or preserved-topology changes were introduced. Downstream deployment/paid-smoke steps remain tracked as open tasks, not part of this implementation review.
Verification: `./gradlew clean quality --no-daemon` (tests, PMD, SpotBugs, static-analysis gate) BUILD SUCCESSFUL; `shellcheck scripts/*.sh` clean; `flyctl config validate` valid; `PORT=<free> scripts/container-smoke.sh` exit 0 (paid-test-mode smoke, 402 challenge then paid 200 + Payment-Receipt); `dependencyInsight` confirms Paygate 0.1.7, Spring Boot 4.0.8, Tomcat 11.0.25; Maven Central 0.1.7 POMs return HTTP 200 for both Paygate modules.

## Outcome
Upgrade implementation committed at fc2dd73 and passed fresh-context review Attempt 1 at 7f14369713966c3eb5bd3413815532f777a45c9c. [build.gradle.kts](../build.gradle.kts) selects both Paygate modules 0.1.7, Boot 4.0.8, and an explicit Tomcat 11.0.25 override because Boot's BOM selects 11.0.24; [gradle.properties](../gradle.properties) and [CHANGELOG.md](../CHANGELOG.md) prepare service v0.1.5. Existing [production migration and rollback guidance](../docs/PRODUCTION-RUNBOOK.md) remains applicable; no new behavior warranted a runbook or test change. Maven Central POM checks, runtime dependency insight, `./gradlew clean quality --no-daemon`, shellcheck, Fly config validation, and container paid-test-mode smoke on port 18087 passed (default port 18080 was occupied). Review found no issues and scoped files remain clean and unchanged since the reviewed revision. This closes the *reviewed upgrade implementation only*: release PR/CI, merge, tag, Fly health/production smoke, restricted real-sats paid smoke, and protected release finalization remain unverified and undone; follow the [release checklist](../docs/RELEASE-CHECKLIST.md) before claiming production success.

## Next action
Open and validate the reviewed v0.1.5 release PR following the [service release checklist](../docs/RELEASE-CHECKLIST.md).
