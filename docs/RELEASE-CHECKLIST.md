# Service Release Checklist

## Release PR

- [ ] Sync `master`; create `release/X.Y.Z`.
- [ ] Set `version=X.Y.Z` in `gradle.properties` and finalize `CHANGELOG.md`.
- [ ] Run `./gradlew clean build --no-daemon`, `shellcheck scripts/*.sh`, and `scripts/container-smoke.sh`.
- [ ] Run `flyctl config validate` without printing secrets.
- [ ] Confirm `production` has an app-scoped expiring `FLY_API_TOKEN`, reviewed `REPORT_SIGNING_KEY_ID` variable, approval protection, and tag restriction `v*`.
- [ ] Merge the green, reviewed release PR before tagging.

## Tag and deploy

- [ ] Sync merged `master`; confirm the version and SHA.
- [ ] Create and push `git tag -a vX.Y.Z -m "Release vX.Y.Z"`.
- [ ] Monitor the serialized tagged-release workflow and Fly routing checks.
- [ ] From a restricted runner, run the Breez paid smoke and record it in the GitHub Release.
- [ ] Record tag, Git SHA, Fly image/release metadata, health output, and paid-smoke result.

## Next snapshot

- [ ] Create `chore/start-X.Y.N-snapshot`, set `version=X.Y.N-SNAPSHOT`, and merge by normal PR.
- [ ] Never move or reuse a published tag.
