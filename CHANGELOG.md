# Changelog

## [Unreleased]

## [0.1.1] - 2026-07-15

- Fixed production startup by giving the non-root runtime user a writable home and persisting Paygate macaroon root keys on an encrypted Fly volume.
- Excluded the CI-created Trivy cache from Docker contexts and made the Fly deploy context and configuration explicit.

## [0.1.0] - 2026-07-15

- Added signed trust reports, LNbits payment enforcement, public verification, bounded outbound checks, and rate limits.
- Added production validation, deterministic non-root containers, Fly deployment safety, tagged releases, and Breez paid verification.
- Added protected dependency review and repeatable, non-deploying Fly production-secret staging.

[Unreleased]: https://github.com/greenharborlabs/paygate-agent-trust/compare/v0.1.1...HEAD
[0.1.1]: https://github.com/greenharborlabs/paygate-agent-trust/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/greenharborlabs/paygate-agent-trust/releases/tag/v0.1.0
