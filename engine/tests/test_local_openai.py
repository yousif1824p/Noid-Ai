import unittest

import httpx

from noid_engine.local_openai import LocalOpenAICompatibleEngine
from noid_engine.engine_contract import EngineUnavailable


class EventStream(httpx.AsyncByteStream):
    def __init__(self, body: bytes):
        self.body = body

    def __aiter__(self):
        async def iterator():
            yield self.body
        return iterator()

    async def aclose(self):
        return None


class LocalOpenAICompatibleEngineTests(unittest.IsolatedAsyncioTestCase):
    def test_rejects_non_loopback_origins_and_url_credentials(self):
        for url in (
            "http://example.com:8080",
            "http://user:pass@127.0.0.1:8080",
            "http://127.0.0.1:8080/api",
            "ftp://127.0.0.1:8080",
        ):
            with self.subTest(url=url), self.assertRaises(ValueError):
                LocalOpenAICompatibleEngine(url, "local-model")

    def test_accepts_loopback_origin(self):
        engine = LocalOpenAICompatibleEngine("http://127.0.0.1:8080/", "local-model")
        self.assertEqual(engine.base_url, "http://127.0.0.1:8080")

    async def test_parses_openai_compatible_sse(self):
        body = (
            b'data: {"choices":[{"delta":{"content":"Hello"}}]}\n\n'
            b'data: {"choices":[{"delta":{"content":" local"}}]}\n\n'
            b'data: [DONE]\n\n'
        )
        engine = LocalOpenAICompatibleEngine("http://127.0.0.1:8080", "local-model")

        async def handler(request):
            self.assertEqual(request.url.path, "/v1/chat/completions")
            self.assertEqual(request.headers["accept"], "text/event-stream")
            payload = __import__("json").loads(request.content)
            self.assertEqual(payload["model"], "local-model")
            self.assertTrue(payload["stream"])
            return httpx.Response(200, stream=EventStream(body))

        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        original = httpx.AsyncClient
        try:
            httpx.AsyncClient = lambda **kwargs: client
            fragments = [part async for part in engine.stream([
                {"role": "user", "content": "Hi"}
            ])]
        finally:
            httpx.AsyncClient = original
        self.assertEqual(fragments, ["Hello", " local"])

    async def test_requires_done_marker(self):
        body = b'data: {"choices":[{"delta":{"content":"partial"}}]}\n'
        engine = LocalOpenAICompatibleEngine("http://localhost:8080", "local-model")
        client = httpx.AsyncClient(transport=httpx.MockTransport(
            lambda request: httpx.Response(200, stream=EventStream(body))
        ))
        original = httpx.AsyncClient
        try:
            httpx.AsyncClient = lambda **kwargs: client
            with self.assertRaises(EngineUnavailable):
                _ = [part async for part in engine.stream([
                    {"role": "user", "content": "Hi"}
                ])]
        finally:
            httpx.AsyncClient = original

    async def test_rejects_malformed_events(self):
        for body in (b"data: not-json\n", b'data: {"choices":[]}\n'):
            engine = LocalOpenAICompatibleEngine("http://127.0.0.1:8080", "local-model")
            client = httpx.AsyncClient(transport=httpx.MockTransport(
                lambda request, payload=body: httpx.Response(
                    200, stream=EventStream(payload + b"data: [DONE]\n")
                )
            ))
            original = httpx.AsyncClient
            try:
                httpx.AsyncClient = lambda **kwargs: client
                with self.assertRaises(EngineUnavailable):
                    _ = [part async for part in engine.stream([
                        {"role": "user", "content": "Hi"}
                    ])]
            finally:
                httpx.AsyncClient = original

    async def test_rejects_oversized_sse_line(self):
        engine = LocalOpenAICompatibleEngine("http://127.0.0.1:8080", "local-model")
        body = b"x" * (engine.MAX_LINE_BYTES + 1)
        client = httpx.AsyncClient(transport=httpx.MockTransport(
            lambda request: httpx.Response(200, stream=EventStream(body))
        ))
        original = httpx.AsyncClient
        try:
            httpx.AsyncClient = lambda **kwargs: client
            with self.assertRaises(EngineUnavailable):
                _ = [part async for part in engine.stream([
                    {"role": "user", "content": "Hi"}
                ])]
        finally:
            httpx.AsyncClient = original

    async def test_rejects_provider_http_errors(self):
        engine = LocalOpenAICompatibleEngine("http://127.0.0.1:8080", "local-model")
        client = httpx.AsyncClient(transport=httpx.MockTransport(
            lambda request: httpx.Response(503)
        ))
        original = httpx.AsyncClient
        try:
            httpx.AsyncClient = lambda **kwargs: client
            with self.assertRaises(EngineUnavailable):
                _ = [part async for part in engine.stream([
                    {"role": "user", "content": "Hi"}
                ])]
        finally:
            httpx.AsyncClient = original


if __name__ == "__main__":
    unittest.main()
