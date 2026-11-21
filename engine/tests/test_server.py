from __future__ import annotations

import json
import unittest
from collections.abc import AsyncIterator, Sequence

import httpx

from noid_engine.server import MAX_BODY_BYTES, MAX_OUTPUT_CHARS, create_app


class FakeEngine:
    def __init__(self, fragments: Sequence[str] = ("مرحباً",)):
        self.fragments = fragments

    async def stream(self, messages: Sequence[dict[str, str]]) -> AsyncIterator[str]:
        for fragment in self.fragments:
            yield fragment


class RemoteEngineApiTests(unittest.IsolatedAsyncioTestCase):
    def make_client(self, engine=None):
        app = create_app(engine=engine, token="test-secret")
        transport = httpx.ASGITransport(app=app)
        return httpx.AsyncClient(transport=transport, base_url="http://noid.test")

    def headers(self):
        return {"Authorization": "Bearer test-secret"}

    async def test_health_requires_authentication(self):
        async with self.make_client(FakeEngine()) as client:
            response = await client.get("/v1/health")
        self.assertEqual(response.status_code, 401)
        self.assertEqual(response.json()["error_code"], "unauthorized")

    async def test_unconfigured_engine_reports_unavailable(self):
        async with self.make_client() as client:
            response = await client.get("/v1/health", headers=self.headers())
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["status"], "unavailable")

    async def test_configured_engine_reports_ready(self):
        async with self.make_client(FakeEngine()) as client:
            response = await client.get("/v1/health", headers=self.headers())
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["status"], "ready")

    async def test_chat_streams_ordered_sse_events(self):
        async with self.make_client(FakeEngine(("أهلاً", " بك"))) as client:
            response = await client.post(
                "/v1/chat",
                headers=self.headers(),
                json={"request_id": "req-1", "messages": [{"role": "user", "content": "قل مرحباً"}]},
            )
        self.assertEqual(response.status_code, 200)
        self.assertIn("text/event-stream", response.headers["content-type"])
        frames = [part for part in response.text.split("\n\n") if part.strip()]
        events = [frame.splitlines()[0].removeprefix("event: ") for frame in frames]
        payloads = [json.loads(next(line[6:] for line in frame.splitlines() if line.startswith("data: "))) for frame in frames]
        self.assertEqual(events, ["started", "delta", "delta", "completed"])
        self.assertEqual([item["sequence"] for item in payloads], [1, 2, 3, 4])
        self.assertEqual("".join(item.get("text", "") for item in payloads), "أهلاً بك")

    async def test_invalid_payload_is_rejected(self):
        async with self.make_client(FakeEngine()) as client:
            response = await client.post("/v1/chat", headers=self.headers(), json={"request_id": "bad id", "messages": []})
        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json()["error_code"], "invalid_request")

    async def test_oversized_content_length_is_rejected(self):
        async with self.make_client(FakeEngine()) as client:
            response = await client.post(
                "/v1/chat",
                headers={**self.headers(), "Content-Length": str(MAX_BODY_BYTES + 1)},
                content=b"{}",
            )
        self.assertEqual(response.status_code, 413)
        self.assertEqual(response.json()["error_code"], "request_too_large")

    async def test_output_limit_terminates_with_stable_error(self):
        async with self.make_client(FakeEngine(("x" * (MAX_OUTPUT_CHARS + 1),))) as client:
            response = await client.post(
                "/v1/chat",
                headers=self.headers(),
                json={"request_id": "req-limit", "messages": [{"role": "user", "content": "test"}]},
            )
        self.assertIn('event: error', response.text)
        self.assertIn('"error_code":"output_limit"', response.text)
        self.assertNotIn("event: completed", response.text)


if __name__ == "__main__":
    unittest.main()
