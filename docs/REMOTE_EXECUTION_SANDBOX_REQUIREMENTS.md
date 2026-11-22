# Remote untrusted-execution sandbox requirements

**Current implementation status (2026-11-22): fail-closed boundary verified; sandbox service NOT implemented.** The remote engine exposes authenticated health, chat, and chat-cancellation routes only. The chat JSON schema accepts exactly `request_id` and `messages`; code, command, files, and tools fields are rejected. There is no `/v1/execute` route. Regression tests cover these boundaries. This prevents accidental activation of remote code execution, but it is not a sandbox and does not satisfy the deployment requirements below.

**Status:** Requirements only. No sandbox server, container runner, code-execution endpoint, or remote execution capability is implemented.

## Security boundary

The Android app must never execute untrusted user/model code on the Android host and must never describe an ordinary process, coroutine, or app-private directory as a sandbox. Any future untrusted execution must occur on a separately administered remote worker whose isolation properties can be independently tested.

The existing remote chat connector is text-generation transport only. It is not an execution channel. Do not add code, shell, filesystem, package-install, or arbitrary-tool fields to its chat request schema.

## Required isolation model

A future execution service must use one disposable worker per job (or an equivalently strong isolation boundary) with:

- A non-root, unprivileged identity; no privileged container mode, host PID/network namespaces, host sockets, or host filesystem mounts.
- A read-only base image and a fresh, size-limited scratch workspace destroyed after each job.
- No host credentials, cloud metadata access, signing keys, Android credentials, or ambient service-account tokens.
- Network disabled by default. If a task requires network, use an explicit destination allowlist, egress proxy, DNS/IP rebinding defenses, and per-job byte/time quotas.
- CPU, memory, process-count, file-size, disk, wall-clock, and output limits enforced outside the worker process.
- Syscall and capability restrictions plus a maintained runtime/image with vulnerability updates.
- No access to Android device APIs, local app data, SAF URIs, user memories, audit history, or other jobs' workspaces.

A container alone is not sufficient evidence of isolation. The deployment must document the host kernel/runtime threat model and use stronger isolation (for example, a microVM) when the threat model requires it.

## Authorization and user experience

- Execution is disabled by default and is a separate capability from remote chat.
- Show the exact code/input, worker environment, network scope, resource limits, retention, and estimated cost before the user confirms a job.
- Every job receives a random opaque ID and a short-lived, single-job credential. Credentials are scoped to that job and revoked at completion/timeout.
- Cancellation must terminate the worker and its descendants, not merely close the Android response stream.
- The app must show queued/running/completed/failed/cancelled states and must never imply that a timed-out job was safely terminated without server confirmation.
- Results are untrusted data. They must not auto-run, modify local files, install packages, or invoke Android tools. Importing an artifact requires a separate user-selected destination and confirmation.

## Data minimization and retention

- Send only the exact user-approved source/input required for that job.
- Never send chat history, durable memories, device identifiers, permissions, audit history, credentials, or unrelated files by default.
- Do not log source code, stdout/stderr, environment variables, secrets, or full request bodies.
- Disclose provider, region, retention, deletion, and backup behavior before first use.
- Default to deleting workspace and artifacts after a short disclosed retention window; provide explicit user deletion and verify it server-side.

## Required server-side verification

Before enabling this capability, test that a job cannot:

1. Read host files, other jobs' files, service credentials, or cloud metadata.
2. Reach loopback, private/link-local networks, unapproved public destinations, or the control plane.
3. Persist processes or files after job completion, cancellation, timeout, or worker reuse.
4. Exhaust host resources beyond enforced quotas.
5. Escape through mounts, namespaces, kernel interfaces, runtime sockets, or artifact extraction.
6. Forge another job's identity, fetch another job's artifacts, or reuse expired credentials.

The service must retain signed, privacy-minimized evidence of the image digest, policy version, resource limits, and terminal worker state for each job.

## Client integration gates

Only after the server passes an independent security review may Android add a separate execution API. That API must use HTTPS, explicit per-job consent, bounded uploads/downloads, short-lived credentials, cancellation, strict response validation, and the existing local audit boundary. It must not share chat bearer tokens or treat model output as user consent.

## Non-claims

This document does not create a sandbox, validate a container configuration, prove isolation, or authorize code execution. Until a real service and its isolation tests exist, NOID must refuse remote untrusted execution and continue to support only the separately implemented text-chat connector.
