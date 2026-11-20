# NOID AI

An independent Android-first personal AI assistant project. The source repository is public by the owner's explicit choice; the app and any APK remain for personal use only.

## Project goals

- Native Android application built with Kotlin and Jetpack Compose.
- Conversational interface for general assistance, programming, visual analysis, and authorized defensive cybersecurity work.
- Local-first foundation with explicit capability boundaries and optional external compute.
- User-controlled memory, transparent actions, and confirmation before sensitive operations.
- Personal APK use only; no public app-store release or APK distribution is planned.

## Safety boundaries

NOID AI is intended for lawful, authorized use. It must not provide cheating, stealth, anti-detection evasion, unauthorized access, or destructive automation. Security testing must be limited to systems the user owns or is explicitly authorized to assess.

## Current status

**Stage 2 — local data and model interface implemented; device validation pending.** The Android chat shell, Room chat persistence, lifecycle-aware ViewModels, user-controlled memory management, a versioned Room migration, validated streaming orchestration, and the pinned LiteRT-LM Android runtime adapter are in the repository. The runtime adapter and model import flow are implemented, but native runtime compatibility and actual inference on the target phone have not yet been verified. The latest development CI run passed unit tests. Development CI runs unit tests only and does not package APKs. APK packaging is intentionally deferred until development and integration are complete and the user explicitly approves the final build.

## Documentation

- [Architecture](docs/ARCHITECTURE.md)
- [Security notice](SECURITY_NOTICE.md)
- [Signing and release key policy](docs/SIGNING.md)
- [Development roadmap](docs/ROADMAP.md)

## Repository security

Never commit signing keys, credentials, tokens, local environment files, private datasets, or generated build outputs. See `.gitignore` and `docs/SIGNING.md`.
