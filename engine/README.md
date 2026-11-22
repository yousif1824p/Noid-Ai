# NOID AI Engine

This directory contains the optional authenticated HTTP/SSE service consumed by the Android remote connector.

## Current implementation

- `GET /v1/health` returns the minimal versioned health contract and reports the selected provider state.
- `POST /v1/chat` validates the exact bounded request schema and streams ordered SSE events.
- `DELETE /v1/chat/{request_id}` requests cancellation of an active generation.
- Bearer authentication is mandatory. Set `NOID_API_TOKEN` outside the repository.
- The engine has a provider registry (`noid_engine.providers.ProviderRegistry`). Adapters can be registered independently of the API server.
- Provider selection is explicit through `NOID_TEXT_PROVIDER`; there is no automatic model selection or remote fallback.
- The only built-in provider adapter at this stage is loopback-only Ollama. It requires both `NOID_OLLAMA_BASE_URL` and `NOID_OLLAMA_MODEL`.
- With no selected provider, an unknown provider, or incomplete provider settings, the service reports `unavailable`. It never fabricates assistant output.
- Model output is plain text only; no tools, shell, files, Android permissions, or code-execution endpoint exist.
- Provider adapters receive only chat messages. APK files, manifests, and inspection reports are not uploaded by this service.

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

The example binds to loopback. For deployment, terminate TLS at a correctly configured reverse proxy, restrict network access, use a high-entropy revocable token, and never expose the service over public cleartext HTTP. Do not log prompts, model output, authorization headers, or tokens.

## Explicit limitations

- Only Ollama is currently registered as a built-in text provider. The registry is extensible, but additional local/cloud adapters are not yet implemented or verified.
- No provider is enabled by default. A model must already be installed and explicitly selected on the server host.
- Provider quality, deployment TLS, multi-worker cancellation, and live Android-to-server interoperability are not yet verified.
- APK security analysis remains a separate deterministic Android-side process. AI advice, when added, must remain separately labelled and must never overwrite deterministic findings or declare an APK safe without adequate evidence.
