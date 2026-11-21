from __future__ import annotations

import asyncio
import hmac
import json
import os
import re
from collections.abc import AsyncIterator, Sequence
from dataclasses import dataclass
from typing import Protocol

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, StreamingResponse

PROTOCOL_VERSION = "1"
MAX_BODY_BYTES = 1_048_576
MAX_MESSAGES = 20
MAX_MESSAGE_CHARS = 32_000
MAX_CONTEXT_BYTES = 65_536
MAX_REQUEST_ID_CHARS = 64
MAX_OUTPUT_CHARS = 32_000
MAX_DELTA_CHARS = 8_000
REQUEST_ID_RE = re.compile(r"^[A-Za-z0-9._~-]{1,64}$")
ALLOWED_ROLES = {"user", "assistant"}


class EngineUnavailable(Exception):
    """Raised when no inference backend is configured."""


class TextEngine(Protocol):
    async def stream(self, messages: Sequence[dict[str, str]]) -> AsyncIterator[str]:
        """Yield plain-text fragments; implementations must not execute tools."""


@dataclass
class ActiveRequest:
    cancel: asyncio.Event
    task: asyncio.Task[object] | None = None


class UnconfiguredEngine:
    async def stream(self, messages: Sequence[dict[str, str]]) -> AsyncIterator[str]:
        raise EngineUnavailable
        yield ""  # pragma: no cover - keeps this an async generator


def _error_response(code: str, status: int) -> JSONResponse:
    return JSONResponse({"error_code": code}, status_code=status)


def _sse(event: str, payload: dict[str, object]) -> bytes:
    data = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
    return f"event: {event}\ndata: {data}\n\n".encode("utf-8")


def _validate_payload(payload: object) -> tuple[str, list[dict[str, str]]] | None:
    if not isinstance(payload, dict) or set(payload) != {"request_id", "messages"}:
        return None
    request_id = payload.get("request_id")
    messages = payload.get("messages")
    if not isinstance(request_id, str) or not REQUEST_ID_RE.fullmatch(request_id):
        return None
    if not isinstance(messages, list) or not 1 <= len(messages) <= MAX_MESSAGES:
        return None
    if not any(isinstance(m, dict) and m.get("role") == "user" for m in messages):
        return None
    normalized: list[dict[str, str]] = []
    context_bytes = 0
    for message in messages:
        if not isinstance(message, dict) or set(message) != {"role", "content"}:
            return None
        role, content = message.get("role"), message.get("content")
        if role not in ALLOWED_ROLES or not isinstance(content, str):
            return None
        if not content.strip() or len(content) > MAX_MESSAGE_CHARS:
            return None
        context_bytes += len(content.encode("utf-8"))
        if context_bytes > MAX_CONTEXT_BYTES:
            return None
        normalized.append({"role": role, "content": content})
    return request_id, normalized


def create_app(engine: TextEngine | None = None, token: str | None = None) -> FastAPI:
    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
    if engine is not None:
        backend = engine
    else:
        ollama_url = os.environ.get("NOID_OLLAMA_BASE_URL", "")
        ollama_model = os.environ.get("NOID_OLLAMA_MODEL", "")
        if ollama_url and ollama_model:
            from .ollama import OllamaTextEngine
            backend = OllamaTextEngine(ollama_url, ollama_model)
        else:
            backend = UnconfiguredEngine()
    backend_configured = not isinstance(backend, UnconfiguredEngine)
    expected_token = token if token is not None else os.environ.get("NOID_API_TOKEN", "")
    active: dict[str, ActiveRequest] = {}
    active_lock = asyncio.Lock()

    async def authorized(request: Request) -> bool:
        supplied = request.headers.get("authorization", "")
        if not expected_token or not supplied.startswith("Bearer "):
            return False
        candidate = supplied[7:]
        return bool(candidate) and hmac.compare_digest(candidate, expected_token)

    @app.get("/v1/health")
    async def health(request: Request) -> JSONResponse:
        if not await authorized(request):
            return _error_response("unauthorized", 401)
        return JSONResponse({
            "protocol": "noid-http-sse",
            "version": PROTOCOL_VERSION,
            "status": "ready" if backend_configured else "unavailable",
        }, headers={"Cache-Control": "no-store"})

    @app.post("/v1/chat")
    async def chat(request: Request):
        if not await authorized(request):
            return _error_response("unauthorized", 401)
        content_length = request.headers.get("content-length")
        if content_length:
            try:
                if int(content_length) > MAX_BODY_BYTES:
                    return _error_response("request_too_large", 413)
            except ValueError:
                return _error_response("invalid_request", 400)
        raw = bytearray()
        async for chunk in request.stream():
            raw.extend(chunk)
            if len(raw) > MAX_BODY_BYTES:
                return _error_response("request_too_large", 413)
        if not raw:
            return _error_response("invalid_request", 400)
        try:
            payload = json.loads(bytes(raw))
        except (UnicodeDecodeError, json.JSONDecodeError):
            return _error_response("invalid_request", 400)
        validated = _validate_payload(payload)
        if validated is None:
            return _error_response("invalid_request", 400)
        request_id, messages = validated
        state = ActiveRequest(cancel=asyncio.Event())
        async with active_lock:
            if request_id in active:
                return _error_response("conflict", 409)
            active[request_id] = state

        async def events() -> AsyncIterator[bytes]:
            sequence = 0
            output_chars = 0
            terminal = False
            try:
                sequence += 1
                yield _sse("started", {
                    "request_id": request_id,
                    "protocol_version": PROTOCOL_VERSION,
                    "sequence": sequence,
                })
                iterator = backend.stream(messages).__aiter__()
                while True:
                    next_task = asyncio.create_task(iterator.__anext__())
                    state.task = next_task
                    cancel_task = asyncio.create_task(state.cancel.wait())
                    done, pending = await asyncio.wait(
                        {next_task, cancel_task},
                        return_when=asyncio.FIRST_COMPLETED,
                    )
                    cancel_task.cancel()
                    await asyncio.gather(cancel_task, return_exceptions=True)
                    if cancel_task in done and state.cancel.is_set():
                        next_task.cancel()
                        await asyncio.gather(next_task, return_exceptions=True)
                        state.task = None
                        sequence += 1
                        terminal = True
                        yield _sse("error", {
                            "request_id": request_id,
                            "protocol_version": PROTOCOL_VERSION,
                            "sequence": sequence,
                            "error_code": "cancelled",
                        })
                        return
                    state.task = None
                    try:
                        fragment = next_task.result()
                    except StopAsyncIteration:
                        break
                    if not isinstance(fragment, str) or not fragment:
                        continue
                    output_chars += len(fragment)
                    if output_chars > MAX_OUTPUT_CHARS:
                        sequence += 1
                        terminal = True
                        yield _sse("error", {
                            "request_id": request_id,
                            "protocol_version": PROTOCOL_VERSION,
                            "sequence": sequence,
                            "error_code": "output_limit",
                        })
                        return
                    for offset in range(0, len(fragment), MAX_DELTA_CHARS):
                        sequence += 1
                        yield _sse("delta", {
                            "request_id": request_id,
                            "protocol_version": PROTOCOL_VERSION,
                            "sequence": sequence,
                            "text": fragment[offset:offset + MAX_DELTA_CHARS],
                        })
                if state.cancel.is_set():
                    sequence += 1
                    terminal = True
                    yield _sse("error", {
                        "request_id": request_id,
                        "protocol_version": PROTOCOL_VERSION,
                        "sequence": sequence,
                        "error_code": "cancelled",
                    })
                else:
                    sequence += 1
                    terminal = True
                    yield _sse("completed", {
                        "request_id": request_id,
                        "protocol_version": PROTOCOL_VERSION,
                        "sequence": sequence,
                    })
            except EngineUnavailable:
                sequence += 1
                terminal = True
                yield _sse("error", {
                    "request_id": request_id,
                    "protocol_version": PROTOCOL_VERSION,
                    "sequence": sequence,
                    "error_code": "unavailable",
                })
            except asyncio.CancelledError:
                state.cancel.set()
                raise
            except Exception:
                sequence += 1
                terminal = True
                yield _sse("error", {
                    "request_id": request_id,
                    "protocol_version": PROTOCOL_VERSION,
                    "sequence": sequence,
                    "error_code": "internal_error",
                })
            finally:
                if not terminal and not state.cancel.is_set():
                    state.cancel.set()
                pending_task = state.task
                if pending_task is not None and not pending_task.done():
                    pending_task.cancel()
                    await asyncio.gather(pending_task, return_exceptions=True)
                state.task = None
                async with active_lock:
                    active.pop(request_id, None)

        return StreamingResponse(
            events(),
            media_type="text/event-stream",
            headers={
                "Cache-Control": "no-store",
                "X-Accel-Buffering": "no",
                "Connection": "keep-alive",
            },
        )

    @app.delete("/v1/chat/{request_id}")
    async def cancel(request_id: str, request: Request) -> JSONResponse:
        if not await authorized(request):
            return _error_response("unauthorized", 401)
        async with active_lock:
            state = active.get(request_id)
            if state is None:
                return _error_response("unavailable", 404)
            state.cancel.set()
        return JSONResponse({"request_id": request_id, "status": "cancellation_requested"},
                            headers={"Cache-Control": "no-store"})

    return app


app = create_app()
