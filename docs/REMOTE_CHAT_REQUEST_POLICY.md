# Remote chat request privacy and size policy

**Status:** Implemented pure validation only. No HTTP client or network request is implemented.

`RemoteChatRequestPolicy` defines a deliberately narrow request shape: an opaque request ID and a bounded list of plain-text `user` / `assistant` messages. It rejects system/tool roles, blank content, missing user input, invalid IDs, more than 20 messages, messages over 32,000 characters, aggregate context over 64 KiB UTF-8, and serialized bodies over 1 MiB.

The request data type has no fields for local memories, files, images, audio, Android permission state, audit history, device identifiers, or tool calls. This reduces accidental inclusion at the request-model boundary; it does not prove what a future adapter serializes. The adapter must serialize only this validated object, call the serialized-body size check, and obtain explicit per-request user consent before connecting.

The policy itself does not perform HTTP, TLS, authentication, credential storage, redirect handling, cancellation, server-side retention controls, or end-to-end privacy verification. `RemoteChatJsonSerializer` now serializes the validated request into exactly `request_id` and `messages` (each containing only `role` and `content`), enforces the serialized-body limit, and has unit tests for exact schema, Unicode round-tripping, unsupported roles, and aggregate UTF-8 boundaries. This is still serialization only; no network connection is made. Remote output remains untrusted and cannot grant local capabilities or invoke tools. Unit tests cover boundaries and rejection paths.
