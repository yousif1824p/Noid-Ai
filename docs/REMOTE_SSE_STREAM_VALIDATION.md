# Remote SSE envelope and lifecycle validation

`RemoteSseStreamValidator` is a pure protocol state machine layered after `RemoteSseFrameDecoder`. The HTTP adapter must parse each frame's JSON into `RemoteSseEnvelope` before passing it to the validator.

It binds every event to the expected request ID and protocol version `1`, requires strictly increasing sequence numbers, enforces `started` before deltas/terminal events, caps aggregate text at 32,000 characters, permits only documented stable error codes, rejects payload fields that do not belong to an event, rejects all events after a terminal event, and requires `finish()` to observe a terminal event.

This does not parse JSON, perform HTTP/TLS, authenticate, persist credentials, cancel a socket, or execute tools. Those remain separate connector work. Remote content remains untrusted and cannot grant local capabilities.
