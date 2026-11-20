# APK Signing and Release Key Policy

NOID AI is intended for private APK distribution.

## Non-negotiable rules

1. The release keystore and its passwords must never enter this repository.
2. Never paste signing secrets into chat, source files, build logs, or issue reports.
3. Keep at least one encrypted offline backup of the release keystore and recovery information.
4. Use the same release key for future updates. Losing it may prevent in-place upgrades.
5. Use CI secret storage only when a release workflow is deliberately configured; do not print secrets in logs.

## Development builds

Debug signing is for development only and is not the permanent release identity.

## Release readiness

Before creating a release, document the application ID, signing configuration, backup verification, versioning policy, and a reproducible build process. Do not claim a signed release exists until a build and signature verification both succeed.
