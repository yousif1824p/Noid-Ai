# Remote HTTPS transport

**Status:** A bounded HTTPS request/response transport, explicit remote-turn path, user-facing connector settings, and optional Android Keystore bearer-token integration are implemented. Remote server deployment, sandboxing, live incremental SSE delivery, and physical-device verification remain pending; this is not a complete deployable remote service.

## Request boundary

- Construction requires the connector to be enabled, a validated HTTPS endpoint, and an explicit user-initiated flag.
- The request body is produced only by `RemoteChatJsonSerializer`, after the existing role, message-count, character, UTF-8 context, and body-size checks.
- The transport sends only `request_id` and plain-text user/assistant messages.
- The app declares Android's normal `INTERNET` permission. It does not request location, contacts, microphone, or storage permissions for this transport.

## Connection protections

- Uses Android's default `HttpsURLConnection` TLS and certificate validation; no custom trust manager or certificate bypass is installed.
- Redirect following is disabled. Only HTTP 200 and `text/event-stream` responses are accepted.
- Connect/read timeouts are bounded by `RemoteConnectionPolicy`.
- The response is capped at 256 KiB, decoded with strict UTF-8 error reporting (malformed byte sequences are rejected), and must pass the existing strict SSE framing, JSON schema, request-ID, protocol-version, sequence, and terminal-event checks.
- Unit tests verify that redirects remain disabled, responses are not cacheable, malformed UTF-8 is rejected, and the connection is closed on success or failure.
- `cancel()` disconnects the active connection and the read loop checks cancellation.
- `RemoteChatTurnAdapter` maps only the latest bounded conversation text to the remote request, collects validated text deltas, rejects remote error/empty results, and exposes cancellation for the owning chat operation.
- `ChatViewModel.sendRemoteMessage(...)` is a distinct opt-in entry point. The normal `sendMessage(...)` path remains local-only. `cancelGeneration()` and conversation deletion forward cancellation to the active remote call.
- The connector settings screen stores the endpoint and opt-in flag in app-private preferences. The user must explicitly enable the connector and use the separate remote-send action; the normal send action remains local-only.
- Bearer tokens are saved only after an explicit button press, encrypted with Android Keystore AES-256-GCM, and can be cleared from the settings screen. Only encrypted token data is stored in preferences. The token is loaded at request time and sent only in the HTTPS `Authorization: Bearer` header. The UI never reads the token back into a text field.
- Endpoint validation requires HTTPS and rejects embedded credentials, query/fragment data, non-default ports, and unsafe paths. Bearer-token syntax rejects whitespace and header injection. Device-level Keystore behavior still requires physical-device verification.
- Remote content is treated as untrusted text/data. It cannot authorize or invoke local tools.

## Deliberate limitations

The call must run off the Android main thread. It currently buffers the complete bounded SSE response before returning validated events; it does not yet deliver deltas live to the UI. Remote server deployment, server-side sandboxing, live incremental SSE UI delivery, and end-to-end physical-device tests remain pending. This implementation is not evidence that a remote service is available or configured.
