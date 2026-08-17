## Deploy Configuration (configured by /setup-deploy)

- Platform: Fly.io (`paygate-agent-trust`, primary region `iad`)
- Production URL: https://paygate-agent-trust.fly.dev
- Deploy workflow: `.github/workflows/release.yml` on immutable `vX.Y.Z` tags
- Deploy status command: `fly status --app paygate-agent-trust`
- Merge method: merge commit through a reviewed release PR
- Project type: Spring Boot API service
- Post-deploy health check: https://paygate-agent-trust.fly.dev/healthz

### Custom deploy hooks

- Pre-merge: `./gradlew clean quality --no-daemon && shellcheck scripts/*.sh && scripts/container-smoke.sh`
- Deploy trigger: merge the release PR into `master`, then create and push an annotated immutable `vX.Y.Z` tag
- Deploy status: monitor `.github/workflows/release.yml`, then run `fly status --app paygate-agent-trust`
- Health check: `curl --fail --silent https://paygate-agent-trust.fly.dev/healthz`
- Post-deploy verification: run `scripts/production-smoke.sh`, then complete the protected Breez paid smoke and finalize-release workflow

## Skill routing

When the user's request matches an available skill, invoke it via the Skill tool. When in doubt, invoke the skill.

Key routing rules:
- Product ideas/brainstorming → invoke /office-hours
- Strategy/scope → invoke /plan-ceo-review
- Architecture → invoke /plan-eng-review
- Design system/plan review → invoke /design-consultation or /plan-design-review
- Full review pipeline → invoke /autoplan
- Bugs/errors → invoke /investigate
- QA/testing site behavior → invoke /qa or /qa-only
- Code review/diff check → invoke /review
- Visual polish → invoke /design-review
- Ship/deploy/PR → invoke /ship or /land-and-deploy
- Save progress → invoke /context-save
- Resume context → invoke /context-restore
- Author a backlog-ready spec/issue → invoke /spec
