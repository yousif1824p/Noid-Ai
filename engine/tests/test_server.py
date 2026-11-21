from __future__ import annotations

import asyncio
import json
import unittest
from collections.abc import AsyncIterator, Sequence

import httpx

from noid_engine.server import MAX_ACTIVE_REQUESTS, MAX_BODY_BYTES, MAX_OUTPUT_CHARS, create_app


class FakeEngine:
    def __init__(self, fragments: Sequence[str] = ("مرحباً",)):
        self.fragments = fragments

    async def stream(self, messages: Sequence[dict[str, str]]) -> AsyncIterator[str]:
        for fragment in self.fragments:
            yield fragment


class BlockingEngine:
    def __init__(self):
        self.started_count = 0
        self.all_started = asyncio.Event()
        self.release = asyncio.Event()

    async def stream(self, messages: Sequence[dict[str, str]]) -> AsyncIterator[str]:
        self.started_count += 1
        if self.started_count >= MAX_ACTIVE_REQUESTS:
            self.all_started.set()
        await self.release.wait()
        yield "done"

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

    async def test_chat_requires_authentication(self):
        async with self.make_client(FakeEngine()) as client:
            response = await client.post(
                "/v1/chat",
                json={"request_id": "req-auth", "messages": [{"role": "user", "content": "hello"}]},
            )
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

    async def test_active_request_limit_and_cancellation_release_capacity(self):
        engine = BlockingEngine()
        async with self.make_client(engine) as client:
            requests = [
                asyncio.create_task(client.post(
                    "/v1/chat",
                    headers=self.headers(),
                    json={
                        "request_id": f"req-active-{index}",
                        "messages": [{"role": "user", "content": "wait"}],
                    },
                ))
                for index in range(MAX_ACTIVE_REQUESTS)
            ]
            await asyncio.wait_for(engine.all_started.wait(), timeout=2)

            rejected = await client.post(
                "/v1/chat",
                headers=self.headers(),
                json={
                    "request_id": "req-over-capacity",
                    "messages": [{"role": "user", "content": "too many"}],
                },
            )
            self.assertEqual(rejected.status_code, 429)
            self.assertEqual(rejected.json()["error_code"], "rate_limited")

            for index in range(MAX_ACTIVE_REQUESTS):
                cancelled = await client.delete(
                    f"/v1/chat/req-active-{index}", headers=self.headers()
                )
                self.assertEqual(cancelled.status_code, 200)
                self.assertEqual(cancelled.json()["status"], "cancellation_requested")

            responses = await asyncio.wait_for(asyncio.gather(*requests), timeout=2)
            for response in responses:
                self.assertIn("event: error", response.text)
                self.assertIn("\"error_code\":\"cancelled\"", response.text)

            engine.release.set()
            follow_up = await client.post(
                "/v1/chat",
                headers=self.headers(),
                json={
                    "request_id": "req-after-cancel",
                    "messages": [{"role": "user", "content": "now available"}],
                },
            )
            self.assertEqual(follow_up.status_code, 200)

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
