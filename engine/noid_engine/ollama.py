from __future__ import annotations

import ipaddress
import json
from collections.abc import AsyncIterator, Sequence
from urllib.parse import urlsplit, urlunsplit

import httpx

from .server import EngineUnavailable, MAX_OUTPUT_CHARS


class OllamaTextEngine:
    """Loopback-only Ollama streaming adapter; no tool or function calling."""

    MAX_LINE_BYTES = 16_384

    def __init__(self, base_url: str, model: str):
        parsed = urlsplit(base_url)
        if (
            parsed.scheme not in {"http", "https"}
            or not parsed.hostname
            or parsed.username is not None
            or parsed.password is not None
            or parsed.query
            or parsed.fragment
            or parsed.path not in {"", "/"}
        ):
            raise ValueError("Ollama URL must be a plain loopback origin.")
        host = parsed.hostname.lower()
        try:
            loopback = ipaddress.ip_address(host).is_loopback
        except ValueError:
            loopback = host == "localhost"
        if not loopback:
            raise ValueError("Ollama must be bound to the local loopback interface.")
        if not model or len(model) > 128 or any(ord(ch) < 32 for ch in model):
            raise ValueError("Ollama model name is invalid.")
        self.base_url = urlunsplit((parsed.scheme, parsed.netloc, "", "", "")).rstrip("/")
        self.model = model

    async def stream(self, messages: Sequence[dict[str, str]]) -> AsyncIterator[str]:
        timeout = httpx.Timeout(connect=2.0, read=30.0, write=5.0, pool=2.0)
        limits = httpx.Limits(max_connections=1, max_keepalive_connections=0)
        client = httpx.AsyncClient(
            timeout=timeout,
            limits=limits,
            follow_redirects=False,
            trust_env=False,
        )
        output_chars = 0
        saw_done = False
        try:
            async with client.stream(
                "POST",
                f"{self.base_url}/api/chat",
                json={"model": self.model, "messages": list(messages), "stream": True},
                headers={"Accept": "application/x-ndjson"},
            ) as response:
                if response.status_code != 200:
                    raise EngineUnavailable
                async for line in response.aiter_lines():
                    if not line:
                        continue
                    if len(line.encode("utf-8")) > self.MAX_LINE_BYTES:
                        raise EngineUnavailable
                    try:
                        event = json.loads(line)
                    except (UnicodeDecodeError, json.JSONDecodeError):
                        raise EngineUnavailable from None
                    if not isinstance(event, dict) or not isinstance(event.get("done"), bool):
                        raise EngineUnavailable
                    message = event.get("message")
                    if message is not None:
                        if not isinstance(message, dict):
                            raise EngineUnavailable
                        fragment = message.get("content", "")
                        if not isinstance(fragment, str):
                            raise EngineUnavailable
                        output_chars += len(fragment)
                        if output_chars > MAX_OUTPUT_CHARS:
                            raise EngineUnavailable
                        if fragment:
                            yield fragment
                    if event["done"]:
                        saw_done = True
                        break
            if not saw_done:
                raise EngineUnavailable
        except (httpx.HTTPError, OSError, TimeoutError):
            raise EngineUnavailable from None
        finally:
            await client.aclose()
