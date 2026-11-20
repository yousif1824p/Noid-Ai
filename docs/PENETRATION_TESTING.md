# Authorized Application Security Testing

## Product goal

Security assessment is a first-class NOID AI capability, alongside chat, coding, and visual analysis. It is designed for applications the user owns or is explicitly authorized to assess. It is not a general-purpose permission bypass or a way to compromise unrelated apps.

## Assessment workflow

1. **Declare the target** — identify the APK/package, version, source (user-selected APK or project tree), and authorization basis. Keep assessment local by default.
2. **Define scope** — select permitted checks and exclude unrelated packages, accounts, and infrastructure. Require renewed approval if the target or scope changes.
3. **Static APK inspection** — inspect manifest declarations, exported components, intent filters, requested permissions, debuggable/backup flags, network security configuration, embedded endpoints, and dependency metadata. Report evidence and confidence; do not infer exploitability from a manifest flag alone.
4. **Source/project review** — inspect only files selected by the user through SAF. Identify insecure storage, unsafe WebView settings, weak TLS configuration, hard-coded secrets, unsafe IPC, and risky component exposure. Mask secrets in reports.
5. **Controlled dynamic checks** — where supported, run non-destructive tests against a dedicated test build or lab target. Require explicit per-test approval for state-changing, account-affecting, or network-active checks. Rate-limit requests and provide a stop control.
6. **Findings and remediation** — each finding records severity, affected component, reproducible evidence, impact, confidence, and a practical fix. Provide a patch preview/diff; never silently alter a project.
7. **Retest** — rerun relevant checks after the user applies a fix and compare results.

## Allowed assessment areas

- APK/package metadata and manifest analysis.
- Permission inventory and least-privilege review.
- Exported activity, service, receiver, and provider configuration review.
- Deep-link and intent validation review.
- Local storage, logging, backup, and secret-handling review.
- WebView and network-security configuration review.
- Dependency and known-vulnerability metadata review, with source and version caveats.
- Authorized testing of an app's own test endpoints and lab environment.
- Reproducible, non-destructive proof-of-concept demonstrations limited to the authorized test target.
- Suggested code fixes, patch diffs, and regression-test recommendations.

## Hard boundaries

NOID must not:
- steal, extract, or reuse another person's credentials, session cookies, tokens, or private data;
- silently grant itself or another app permissions, bypass Android's permission model, or escape an app sandbox;
- exploit third-party apps or infrastructure without authorization;
- hide activity, persist covertly, evade security monitoring/anti-cheat, or disable protective controls;
- deploy malware, spyware, ransomware, or destructive payloads;
- perform mass scanning or automated exploitation of arbitrary targets.

A user-selected APK or project directory is not, by itself, proof of authorization to attack its backend or other users. Android SAF access permits work only within the tree the user selected; it does not grant access to another app's private data.

## Android implementation constraints

- APK inspection should start with Android package metadata APIs and bounded file parsing; do not install or execute an unknown APK as part of static analysis.
- Dynamic testing must use an explicitly configured lab/test target and must not claim that an ordinary Android app can inspect another app's private process memory or internal files.
- Accessibility APIs are for user-visible, user-approved automation, not permission escalation.
- Root-only techniques are not part of the baseline and must never be implied to work on a non-rooted device.
- Network tests must show the destination, test type, and expected side effects before approval.

## Architecture

Keep the assessment engine separate from chat orchestration:
- AssessmentTarget — package/APK/project identity and authorization scope.
- AssessmentCheck — bounded check identifier, required capability, network/state impact, and risk.
- AssessmentFinding — evidence-backed result, severity, confidence, and remediation.
- AssessmentRunner — executes only registered checks after scope and authorization gates.
- AssessmentReport — exportable report with secrets redacted.
- Adapters — static APK parser, selected-project scanner, and opt-in lab/network tester.

Every check must pass the shared tool authorization policy and emit an audit event. The policy gate is not a substitute for Android OS permissions or proof of target authorization. Keep static analysis usable offline; network/dynamic checks remain opt-in.

## Completion criteria

The feature is not complete until there are unit tests for scope validation, path/target boundaries, risk gating, redaction, and finding serialization; integration tests against a deliberately vulnerable sample app; and documented device limitations. Do not mark a check complete based only on a successful build.
