# APK Signing and Release Key Policy

NOID AI is intended for private APK distribution.

## Non-negotiable rules

1. The release keystore and its passwords must never enter this repository.
2. Never paste signing secrets into chat, source files, build logs, or issue reports.
3. Keep at least one encrypted offline backup of the release keystore and recovery information.
4. Use the same release key for future updates. Losing it may prevent in-place upgrades.
5. Use CI secret storage only when a release workflow is deliberately configured; do not print secrets in logs.

## Gradle configuration

The Android module recognizes these four environment variables:

- `NOID_RELEASE_STORE_FILE`: path to the keystore file on the build machine.
- `NOID_RELEASE_STORE_PASSWORD`: keystore password.
- `NOID_RELEASE_KEY_ALIAS`: release key alias.
- `NOID_RELEASE_KEY_PASSWORD`: key password.

The release signing configuration is created only when all four values are present. Inject them at build time from the operating system's secret manager or, if a dedicated protected release workflow is introduced later, from protected CI environment secrets. Do not put values in `gradle.properties`, source files, command-line arguments, shell history, workflow logs, or this repository. The keystore file itself must remain outside the repository; signing-related file extensions and directories are ignored by Git.

No release keystore or passwords are currently provisioned. This configuration does not create a key, sign an APK, or authorize a release. Missing values leave release signing unconfigured.

## Development builds

Debug signing is for development only and is not the permanent release identity.

## Release readiness

Before creating a release, document the application ID, signing configuration, backup verification, versioning policy, and a reproducible build process. Do not claim a signed release exists until a build and signature verification both succeed. APK creation remains disabled during development and requires project completion plus the user's explicit approval.
