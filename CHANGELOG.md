# Changelog

## [Unreleased]

- Upgraded both Paygate modules to 0.1.5 and Spring Boot to 4.0.7.
- Declared the 8 KiB protected-request limit and IPv6 `/64` rate-limit grouping as configurable environment-backed security bounds.
- Expanded payment-flow and container smoke coverage for exact raw-query binding, hardened Paygate failure headers, in-memory test keys, and a complete paid retry with receipt verification.
- Documented the v0.1.5 credential migration, stable-key requirements, and security-sensitive rollback behavior.

## [0.1.2] - 2026-07-16

- Added a public root discovery endpoint for the live Agent Trust service.
- Updated public documentation with the live service flow and release link.

## [0.1.1] - 2026-07-15

- Fixed production startup by giving the non-root runtime user a writable home and persisting Paygate macaroon root keys on an encrypted Fly volume.
- Excluded the CI-created Trivy cache from Docker contexts and made the Fly deploy context and configuration explicit.

## [0.1.0] - 2026-07-15

- Added signed trust reports, LNbits payment enforcement, public verification, bounded outbound checks, and rate limits.
- Added production validation, deterministic non-root containers, Fly deployment safety, tagged releases, and Breez paid verification.
- Added protected dependency review and repeatable, non-deploying Fly production-secret staging.

[Unreleased]: https://github.com/greenharborlabs/paygate-agent-trust/compare/v0.1.2...HEAD
[0.1.2]: https://github.com/greenharborlabs/paygate-agent-trust/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/greenharborlabs/paygate-agent-trust/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/greenharborlabs/paygate-agent-trust/releases/tag/v0.1.0
