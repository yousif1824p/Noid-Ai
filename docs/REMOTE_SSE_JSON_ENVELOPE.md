# Remote SSE JSON envelope parsing

RemoteSseEnvelopeParser parses the JSON data field for one already-decoded SSE frame. It requires exactly request_id, protocol_version, and a positive integral sequence, with only optional text and error_code. The original sequence token is validated before numeric conversion so decimal and exponent notation cannot be normalized into an integer. Unknown fields, implicit type coercion, null optional values, unsupported protocol versions, overlong IDs/text, and out-of-range sequence numbers are rejected.

RemoteSseDocumentValidator composes the bounded frame decoder, strict JSON parser, and request-bound lifecycle state machine. A document is accepted only if every event validates and the stream ends in a valid terminal event.

This is still a pure validation layer, not a network connector: it does not open sockets, perform TLS, authenticate, store credentials, cancel HTTP calls, or invoke tools. All remote text remains untrusted.
