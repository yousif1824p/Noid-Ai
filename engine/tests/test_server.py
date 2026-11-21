import json
import unittest
from collections.abc import AsyncIterator

import httpx

from noid_engine.server import MAX_OUTPUT_CHARS, create_app


class FakeEngine:
    def __init__(self, fragments: list[str]):
        self.fragments = fragments

    async def stream(self, messages) -> AsyncIterator[str]:
        for fragment in self.fragments:
            yield fragment


class EngineServerTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.transport = None

    async def request(self, app, method: str, path: str, **kwargs):
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            return await client.request(method, path, **kwargs)

    def headers(self):
        return {"Authorization": "Bearer test-secret"}

    def payload(self):
        return {"request_id": "req-01", "messages": [{"role": "user", "content": "مرحبا"}]}

    async def test_health_requires_bearer_and_returns_minimal_contract(self):
        app = create_app(FakeEngine(["ok"]), token="test-secret")
        denied = await self.request(app, "GET", "/v1/health")
        self.assertEqual(denied.status_code, 401)
        response = await self.request(app, "GET", "/v1/health", headers=self.headers())
        self.assertEqual(response.json(), {
            "protocol": "noid-http-sse", "version": "1", "status": "ready"
        })
        self.assertEqual(response.headers["cache-control"], "no-store")

    async def test_chat_stream_matches_versioned_sse_contract(self):
        app = create_app(FakeEngine(["أهلاً", " بك"]), token="test-secret")
        response = await self.request(
            app, "POST", "/v1/chat", headers=self.headers(), json=self.payload()
        )
        self.assertEqual(response.status_code, 200)
        self.assertIn("text/event-stream", response.headers["content-type"])
        events = []
        for block in response.text.strip().split("\n\n"):
            lines = block.splitlines()
            event_name = lines[0].removeprefix("event: ")
            data = json.loads(lines[1].removeprefix("data: "))
            events.append((event_name, data))
        self.assertEqual([name for name, _ in events],
                         ["started", "delta", "delta", "completed"])
        self.assertEqual([data["sequence"] for _, data in events], [1, 2, 3, 4])
        self.assertTrue(all(data["request_id"] == "req-01" for _, data in events))
        self.assertTrue(all(data["protocol_version"] == "1" for _, data in events))

    async def test_unconfigured_engine_emits_terminal_unavailable(self):
        app = create_app(token="test-secret")
        response = await self.request(
            app, "POST", "/v1/chat", headers=self.headers(), json=self.payload()
        )
        self.assertIn("event: error", response.text)
        self.assertIn('"error_code":"unavailable"', response.text)

    async def test_rejects_extra_fields_and_invalid_roles(self):
        app = create_app(FakeEngine(["x"]), token="test-secret")
        bad = self.payload() | {"memories": ["must-not-be-accepted"]}
        response = await self.request(app, "POST", "/v1/chat", headers=self.headers(), json=bad)
        self.assertEqual(response.status_code, 400)
        bad_role = {"request_id": "req-02", "messages": [{"role": "system", "content": "x"}]}
        response = await self.request(app, "POST", "/v1/chat", headers=self.headers(), json=bad_role)
        self.assertEqual(response.status_code, 400)

    async def test_rejects_oversized_context(self):
        app = create_app(FakeEngine(["x"]), token="test-secret")
        body = {"request_id": "req-03", "messages": [
            {"role": "user", "content": "ا" * 20_000},
            {"role": "assistant", "content": "ب" * 20_000},
        ]}
        response = await self.request(app, "POST", "/v1/chat", headers=self.headers(), json=body)
        self.assertEqual(response.status_code, 400)

    async def test_output_limit_emits_error_without_leaking_backend_exception(self):
        app = create_app(FakeEngine(["x" * (MAX_OUTPUT_CHARS + 1)]), token="test-secret")
        response = await self.request(
            app, "POST", "/v1/chat", headers=self.headers(), json=self.payload()
        )
        self.assertIn('"error_code":"output_limit"', response.text)
        self.assertNotIn("Traceback", response.text)

    async def test_invalid_auth_never_reaches_engine(self):
        class MustNotRun:
            async def stream(self, messages):
                raise AssertionError("backend must not be called")
                yield ""
        app = create_app(MustNotRun(), token="test-secret")
        response = await self.request(app, "POST", "/v1/chat", json=self.payload())
        self.assertEqual(response.status_code, 401)


if __name__ == "__main__":
    unittest.main()
