# Remote connection endpoint policy

RemoteConnectionPolicy is a fail-closed configuration validator for a future opt-in connector. It requires explicit enablement and a bounded HTTPS endpoint, rejects embedded credentials, query strings, fragments, non-default ports, malformed hosts, path traversal/encoded separators, and out-of-range connect/read timeouts.

Disabled configuration must not carry an active endpoint. The policy returns a parsed URI only after validation.

This is not a network implementation. It does not create sockets, establish TLS, authenticate, store secrets, resolve DNS, defend against DNS rebinding, cancel requests, or guarantee server identity beyond the platform TLS stack. The caller must still require a user-started request, use platform certificate validation, keep secrets in secure storage, enforce response/body limits, and treat all remote output as untrusted. It grants no Android capabilities and cannot authorize local tools.
