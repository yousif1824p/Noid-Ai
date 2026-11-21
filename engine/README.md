# NOID AI Engine

This directory contains the optional authenticated HTTP/SSE service consumed by the Android remote connector.

## Current implementation

- `GET /v1/health` returns the minimal versioned health contract.
- `POST /v1/chat` validates the exact bounded request schema and streams ordered SSE events.
- `DELETE /v1/chat/{request_id}` requests cancellation of an active generation.
- Bearer authentication is mandatory. Set `NOID_API_TOKEN` outside the repository.
- The default engine is deliberately unconfigured and returns the stable `unavailable` error; it never fabricates assistant output.
- A model provider must implement the `TextEngine.stream(messages)` protocol and be injected into `create_app`. Model output is plain text only; no tools, shell, files, Android permissions, or code-execution endpoint exist.

## Run

Use Python 3.12+ and install `.[test]` for tests or the project dependencies for the service.

```sh
cd engine
python -m pip install -e '.[test]'
NOID_API_TOKEN='replace-with-a-secret-from-your-secret-manager' uvicorn noid_engine.server:app --host 127.0.0.1 --port 8000
python -m unittest discover -s tests -v
```

The example binds to loopback. For deployment, terminate TLS at a correctly configured reverse proxy, restrict network access, use a high-entropy revocable token, and never expose the service over public cleartext HTTP. Do not log prompts, model output, authorization headers, or tokens.

## Explicit limitation

The HTTP/SSE server and protocol boundary are implemented; a production inference provider, TLS deployment, persistent cancellation across workers, and live Android-to-server interoperability are not yet configured or verified. The default app reports unavailable until a real provider is installed.
