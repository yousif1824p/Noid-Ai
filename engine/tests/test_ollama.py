import unittest

import httpx

from noid_engine.ollama import OllamaTextEngine
from noid_engine.server import EngineUnavailable


class Lines(httpx.AsyncByteStream):
    def __init__(self, body: bytes):
        self.body = body

    def __aiter__(self):
        async def iterator():
            yield self.body
        return iterator()

    async def aclose(self):
        return None


class OllamaEngineTests(unittest.IsolatedAsyncioTestCase):
    def test_rejects_non_loopback_and_credentials(self):
        for url in (
            "http://example.com:11434",
            "http://user:pass@127.0.0.1:11434",
            "http://127.0.0.1:11434/path",
            "ftp://127.0.0.1:11434",
        ):
            with self.subTest(url=url), self.assertRaises(ValueError):
                OllamaTextEngine(url, "test-model")

    def test_accepts_loopback_origin_only(self):
        engine = OllamaTextEngine("http://127.0.0.1:11434/", "qwen2.5:3b")
        self.assertEqual(engine.base_url, "http://127.0.0.1:11434")

    async def test_parses_streaming_ndjson_and_requires_done(self):
        body = (
            b'{"message":{"content":"Hello"},"done":false}\n'
            b'{"message":{"content":" world"},"done":true}\n'
        )
        engine = OllamaTextEngine("http://127.0.0.1:11434", "test")
        async def handler(request):
            self.assertEqual(request.url.path, "/api/chat")
            self.assertEqual(request.headers["accept"], "application/x-ndjson")
            return httpx.Response(200, stream=Lines(body))
        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        engine_client = engine
        original = httpx.AsyncClient
        try:
            httpx.AsyncClient = lambda **kwargs: client
            fragments = [part async for part in engine_client.stream([
                {"role": "user", "content": "Hi"}
            ])]
        finally:
            httpx.AsyncClient = original
        self.assertEqual(fragments, ["Hello", " world"])

    async def test_rejects_malformed_or_unterminated_provider_stream(self):
        for body in (b"not-json\n", b'{"message":{"content":"partial"},"done":false}\n'):
            engine = OllamaTextEngine("http://localhost:11434", "test")
            async def handler(request, payload=body):
                return httpx.Response(200, stream=Lines(payload))
            client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
            original = httpx.AsyncClient
            try:
                httpx.AsyncClient = lambda **kwargs: client
                with self.assertRaises(EngineUnavailable):
                    _ = [part async for part in engine.stream([
                        {"role": "user", "content": "Hi"}
                    ])]
            finally:
                httpx.AsyncClient = original

    async def test_rejects_oversized_unterminated_provider_line(self):\n        engine = OllamaTextEngine("http://127.0.0.1:11434", "test")\n        client = httpx.AsyncClient(transport=httpx.MockTransport(\n            lambda request: httpx.Response(200, stream=Lines(b"x" * (engine.MAX_LINE_BYTES + 1)))\n        ))\n        original = httpx.AsyncClient\n        try:\n            httpx.AsyncClient = lambda **kwargs: client\n            with self.assertRaises(EngineUnavailable):\n                _ = [part async for part in engine.stream([{"role": "user", "content": "Hi"}])]\n        finally:\n            httpx.AsyncClient = original\n\n    async def test_rejects_non_success_provider_status(self):
        engine = OllamaTextEngine("http://127.0.0.1:11434", "test")
        async def handler(request):
            return httpx.Response(503)
        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
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
