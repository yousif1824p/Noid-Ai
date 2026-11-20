# Optional Remote Engine HTTP/SSE Contract

**Status:** Design specification only. No remote server, connector, endpoint, or network transport is implemented by this document.

## 1. Goals and non-goals

The remote engine is optional. NOID must remain usable in its supported local-only mode when no remote endpoint is configured or the network is unavailable. A remote model may provide reasoning or generation, but it is never an authority for Android permissions or device actions.

This contract does not provide arbitrary shell access, remote file browsing, remote code execution, security-scanner target modification, or a way to bypass Android's application sandbox. gRPC is deferred until measured requirements justify it.

## 2. Versioning and transport

- Base path: `/v1`; incompatible changes require a new major path (for example, `/v2`).
- Production connections MUST use HTTPS with normal platform certificate validation. Cleartext HTTP is prohibited outside an explicitly isolated developer test fixture and MUST never be enabled in production.
- The client MUST use bounded connect, read, and overall request timeouts; cancellation must close the active stream.
- Do not place credentials, prompts, personal data, or model content in URLs or query parameters.
- Do not disable certificate validation. Certificate pinning is optional and, if adopted, requires a documented rotation and recovery plan.

## 3. Opt-in and authentication

- Remote use is disabled by default and requires an explicit user action plus a visible endpoint and provider disclosure.
- The endpoint must be syntactically valid HTTPS and may not silently redirect to a different origin. Cross-origin redirects are rejected.
- Authenticate with a short-lived bearer credential where supported. Store credentials only in app-private storage protected by Android Keystore-backed encryption; never commit credentials or include them in diagnostics.
- Tokens must be scoped to the NOID service, revocable, and replaceable. Never reuse Android permission grants as remote credentials.
- The UI must offer disconnect/revoke and a local-only mode.

## 4. Endpoints

### `GET /v1/health`

Returns only service readiness and protocol compatibility, for example:

```json
{"protocol":"noid-http-sse","version":"1","status":"ready"}
```

It MUST NOT return hostnames, filesystem paths, environment variables, installed packages, secrets, or detailed infrastructure metadata.

### `POST /v1/chat`

Accepts a bounded JSON request. Initial proposed limits:

- Request body: 1 MiB maximum.
- User text: 32,000 Unicode characters maximum.
- Conversation context: 20 messages maximum and 64 KiB combined text maximum.
- Client-generated request ID: opaque, non-secret, maximum 64 characters.
- No image, audio, file, or other binary upload by default. Future media transfer requires a separate opt-in, explicit per-request consent, content-type allowlist, and independent size limits.

The request contains only the minimum conversation context required. It MUST NOT include Android permission state, audit history, local memory, files, or device identifiers unless a future feature obtains separate informed consent and defines a data-minimization policy.

### `DELETE /v1/chat/{request_id}`

Requests cancellation of an active generation. Cancellation is best-effort and must not be reported as completed until the client observes a terminal event or a documented cancellation response. The server must stop work promptly and release resources.

## 5. Server-Sent Events

The chat response uses `Content-Type: text/event-stream`, UTF-8, and no-store caching. Each event carries the request ID and a monotonically increasing sequence number.

Supported event names:

- `started`: generation accepted; includes request ID and protocol version.
- `delta`: bounded text fragment; clients enforce an aggregate output cap of 32,000 characters.
- `completed`: exactly one terminal success event.
- `error`: exactly one terminal failure event with a stable, non-sensitive error code.

The client rejects malformed JSON, unknown protocol versions, out-of-order or duplicate sequence numbers, oversized events, multiple terminal events, text after a terminal event, and streams that end without a terminal event. Reconnection must not duplicate already-applied deltas; resumability is not required for the first implementation. The client must treat all remote text as untrusted content.

## 6. Error model

Use stable codes such as `unauthorized`, `forbidden`, `invalid_request`, `request_too_large`, `rate_limited`, `unavailable`, `timeout`, `cancelled`, and `internal_error`. Error messages shown to users must be bounded and must not expose stack traces, credentials, filesystem paths, or provider internals. Retry only transient errors, with bounded backoff and no automatic replay of non-idempotent actions.

## 7. Trust and device-action boundary

- Remote output is advisory and untrusted. It cannot grant capabilities or change local policy.
- Every device action remains implemented locally and must pass the existing capability policy, explicit confirmation rules, and local audit logging.
- The remote API has no endpoint for executing arbitrary tools, shell commands, scripts, or device actions.
- A remote response that proposes a sensitive action must be presented as a proposal. The local permission gate independently decides whether the action is available and whether confirmation is required.
- Remote text must never be interpreted as executable code or as an instruction to weaken policy.

## 8. Privacy, logging, and retention

- No conversation or media is uploaded before an explicit user action.
- Do not log request bodies, prompts, model output, bearer tokens, memory contents, file contents, or image/audio bytes.
- Operational logs may contain request IDs, timestamps, coarse duration, protocol version, and sanitized error codes only.
- The UI must disclose which provider receives data and provide a clear remote/local indicator.
- Server retention and deletion behavior must be disclosed before enabling the connector. The first implementation should request no server-side conversation retention.

## 9. Acceptance tests before implementation is considered complete

- HTTPS required; invalid certificates and cross-origin redirects rejected.
- Remote connector disabled until explicit opt-in; disconnect returns to local-only mode.
- Credentials encrypted at rest and absent from logs, URLs, crash reports, and repository history.
- Request, event, context, and output limits enforced on both client and server.
- Duplicate, malformed, out-of-order, post-terminal, and unterminated SSE streams rejected.
- Cancellation stops generation and releases the stream.
- Remote content cannot invoke a tool, grant a capability, or bypass local confirmation and audit.
- Offline and server-error paths preserve local chat and local data.
- No APK build or release packaging is part of contract verification.

## 10. Implementation status

This document defines the proposed contract only. HTTP client, SSE parser, server, authentication UI, secure credential storage, and end-to-end tests remain unimplemented and must be tracked separately.
