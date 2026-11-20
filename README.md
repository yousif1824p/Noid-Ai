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

The Android app includes local chat persistence, user-approved memory, LiteRT-LM local and multimodal vision adapters, tool approval/audit, SAF project-file operations, static APK/source assessment, voice setup/manual dictation, and an opt-in remote HTTPS/SSE client. Target-device local/vision inference and continuous microphone hotword capture are not verified; a real Arabic «يا NOID» detector is not integrated. Remote-client unit tests use controlled HTTPS connections; live-server interoperability remains pending. CI runs Python DSP/dataset checks and Android unit tests only; it does not package APKs. APK packaging remains deferred until development is complete and the user explicitly approves the final build.

## Documentation

- [Architecture](docs/ARCHITECTURE.md)
- [Security notice](SECURITY_NOTICE.md)
- [Signing and release key policy](docs/SIGNING.md)
- [Development roadmap](docs/ROADMAP.md)

## Repository security

Never commit signing keys, credentials, tokens, local environment files, private datasets, or generated build outputs. See `.gitignore` and `docs/SIGNING.md`.
