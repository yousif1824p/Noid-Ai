# NOID AI Engine

This directory contains the optional authenticated HTTP/SSE service consumed by the Android remote connector.

## Current implementation

- `GET /v1/health` returns the minimal versioned health contract and reports the selected provider state.
- `POST /v1/chat` validates the exact bounded request schema and streams ordered SSE events.
- `DELETE /v1/chat/{request_id}` requests cancellation of an active generation.
- Bearer authentication is mandatory. Set `NOID_API_TOKEN` outside the repository.
- The engine has a provider registry (`noid_engine.providers.ProviderRegistry`). Adapters can be registered independently of the API server.
- Provider selection is explicit through `NOID_TEXT_PROVIDER`; there is no automatic model selection or remote fallback.
- Two built-in providers are available: loopback-only Ollama and a loopback-only OpenAI-compatible local inference adapter (for example, a local llama.cpp server). Both require explicit selection and model configuration.
- Ollama uses `NOID_OLLAMA_BASE_URL` and `NOID_OLLAMA_MODEL`; the OpenAI-compatible adapter uses `NOID_LOCAL_OPENAI_BASE_URL` and `NOID_LOCAL_OPENAI_MODEL`.
- With no selected provider, an unknown provider, or incomplete provider settings, the service reports `unavailable`. It never fabricates assistant output.
- Model output is plain text only; no tools, shell, files, Android permissions, or code-execution endpoint exist.
- Provider adapters receive only chat messages. APK files, manifests, and inspection reports are not uploaded by this service.
- `noid_engine.security_evidence` implements an evidence aggregator by check ID, reports contradictory observations, and keeps AI advisories outside authoritative findings. Regression tests are committed but have not yet been run.
- Empty evidence is `insufficient_evidence`; a clean result is explicitly limited to the checks observed. The aggregator has no `safe` status.

## Run

Use Python 3.12+ and install `.[test]` for tests or the project dependencies for the service.

```sh
cd engine
python -m pip install -e '.[test]'
NOID_API_TOKEN='replace-with-a-secret-from-your-secret-manager' \
NOID_TEXT_PROVIDER=ollama \
NOID_OLLAMA_BASE_URL=http://127.0.0.1:11434 \
NOID_OLLAMA_MODEL='your-installed-model' \
uvicorn noid_engine.server:app --host 127.0.0.1 --port 8000
python -m unittest discover -s tests -v
```

For a local OpenAI-compatible server instead, explicitly select `NOID_TEXT_PROVIDER=local_openai` and set `NOID_LOCAL_OPENAI_BASE_URL=http://127.0.0.1:8080` plus `NOID_LOCAL_OPENAI_MODEL='your-local-model'`. The adapter rejects non-loopback origins and does not follow redirects or use proxy environment variables.

The example binds to loopback. For deployment, terminate TLS at a correctly configured reverse proxy, restrict network access, use a high-entropy revocable token, and never expose the service over public cleartext HTTP. Do not log prompts, model output, authorization headers, or tokens.

## Explicit limitations

- Two local adapters are registered: Ollama and an OpenAI-compatible local server adapter. Neither is enabled by default; additional cloud adapters are not implemented.
- No provider is enabled by default. A model must already be installed and explicitly selected on the server host.
- Provider quality, compatibility with specific local server implementations, deployment TLS, multi-worker cancellation, and live Android-to-server interoperability are not yet verified. Adapter tests are committed but have not been run because Actions quota is being preserved.
- The Android APK analyzer currently emits deterministic Manifest findings with explicit source labels. The Python evidence aggregator is implemented as a foundation for future cross-source assessment; it is not yet wired into the Android APK report flow, and its new tests have not yet been run.
- AI advice remains a separate advisory channel. It cannot establish a clean result, override deterministic evidence, or declare an APK safe.
- GitHub Actions automatic push and pull-request triggers are paused to preserve the repository quota. The tests-only workflow remains available through manual `workflow_dispatch`; no APK packaging job is present.
