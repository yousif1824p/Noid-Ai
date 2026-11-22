from __future__ import annotations

import os
import unittest
from collections.abc import AsyncIterator, Sequence
from unittest.mock import patch

import httpx

from noid_engine.providers import ProviderRegistry
from noid_engine.server import create_app


class StubEngine:
    async def stream(self, messages: Sequence[dict[str, str]]) -> AsyncIterator[str]:
        yield "registered"


class ProviderConfigurationApiTests(unittest.IsolatedAsyncioTestCase):
    def make_client(self, registry: ProviderRegistry | None = None):
        return httpx.AsyncClient(
            transport=httpx.ASGITransport(app=create_app(token="test-secret", provider_registry=registry)),
            base_url="http://noid.test",
        )

    async def test_no_provider_means_unavailable_and_does_not_auto_select_ollama(self):
        settings = {
            "NOID_OLLAMA_BASE_URL": "http://127.0.0.1:11434",
            "NOID_OLLAMA_MODEL": "installed-model",
        }
        with patch.dict(os.environ, settings, clear=True):
            async with self.make_client() as client:
                response = await client.get(
                    "/v1/health", headers={"Authorization": "Bearer test-secret"}
                )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["status"], "unavailable")
        self.assertEqual(response.json()["provider"], "none")

    async def test_unknown_provider_is_reported_and_never_falls_back(self):
        with patch.dict(os.environ, {"NOID_TEXT_PROVIDER": "not-installed"}, clear=True):
            async with self.make_client() as client:
                response = await client.get(
                    "/v1/health", headers={"Authorization": "Bearer test-secret"}
                )
        self.assertEqual(response.json()["status"], "unavailable")
        self.assertEqual(response.json()["configuration_error"], "unknown_provider")

    async def test_explicit_registered_provider_is_selected(self):
        registry = ProviderRegistry()
        registry.register("stub", lambda settings: StubEngine())
        with patch.dict(os.environ, {"NOID_TEXT_PROVIDER": "stub"}, clear=True):
            async with self.make_client(registry) as client:
                response = await client.get(
                    "/v1/health", headers={"Authorization": "Bearer test-secret"}
                )
        self.assertEqual(response.json()["status"], "ready")
        self.assertEqual(response.json()["provider"], "stub")

    async def test_missing_selected_provider_configuration_stays_unavailable(self):
        with patch.dict(os.environ, {
            "NOID_TEXT_PROVIDER": "ollama",
            "NOID_OLLAMA_BASE_URL": "",
            "NOID_OLLAMA_MODEL": "",
        }, clear=True):
            async with self.make_client() as client:
                response = await client.get(
                    "/v1/health", headers={"Authorization": "Bearer test-secret"}
                )
        self.assertEqual(response.json()["status"], "unavailable")
        self.assertEqual(
            response.json()["configuration_error"], "provider_configuration_incomplete"
        )



    async def test_local_openai_provider_requires_explicit_configuration(self):
        with patch.dict(os.environ, {"NOID_TEXT_PROVIDER": "local_openai"}, clear=True):
            async with self.make_client() as client:
                response = await client.get(
                    "/v1/health", headers={"Authorization": "Bearer test-secret"}
                )
        self.assertEqual(response.json()["status"], "unavailable")
        self.assertEqual(
            response.json()["configuration_error"], "provider_configuration_incomplete"
        )

    async def test_local_openai_provider_rejects_non_loopback_url(self):
        with patch.dict(os.environ, {
            "NOID_TEXT_PROVIDER": "local_openai",
            "NOID_LOCAL_OPENAI_BASE_URL": "https://example.com",
            "NOID_LOCAL_OPENAI_MODEL": "local-model",
        }, clear=True):
            async with self.make_client() as client:
                response = await client.get(
                    "/v1/health", headers={"Authorization": "Bearer test-secret"}
                )
        self.assertEqual(response.json()["status"], "unavailable")
        self.assertEqual(
            response.json()["configuration_error"], "provider_configuration_invalid"
        )

if __name__ == "__main__":
    unittest.main()
