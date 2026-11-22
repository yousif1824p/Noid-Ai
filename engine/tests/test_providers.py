from __future__ import annotations

import unittest
from collections.abc import AsyncIterator, Mapping, Sequence

from noid_engine.providers import (
    ProviderConfigurationError,
    ProviderRegistry,
    UnknownProviderError,
)


class StubEngine:
    async def stream(self, messages: Sequence[dict[str, str]]) -> AsyncIterator[str]:
        yield "stub"


class ProviderRegistryTests(unittest.TestCase):
    def test_registry_starts_empty_and_does_not_choose_a_provider(self):
        registry = ProviderRegistry()
        self.assertEqual(registry.names(), ())

    def test_explicit_provider_selection_returns_registered_engine(self):
        registry = ProviderRegistry()
        expected = StubEngine()
        registry.register("stub", lambda settings: expected)
        self.assertIs(registry.create("stub", {}), expected)

    def test_provider_names_are_normalized(self):
        registry = ProviderRegistry()
        expected = StubEngine()
        registry.register(" Stub ", lambda settings: expected)
        self.assertIs(registry.create("STUB", {}), expected)
        self.assertEqual(registry.names(), ("stub",))

    def test_unknown_provider_is_rejected_instead_of_silently_falling_back(self):
        registry = ProviderRegistry()
        with self.assertRaises(UnknownProviderError):
            registry.create("missing", {})

    def test_duplicate_provider_registration_is_rejected(self):
        registry = ProviderRegistry()
        registry.register("stub", lambda settings: StubEngine())
        with self.assertRaises(ValueError):
            registry.register("stub", lambda settings: StubEngine())

    def test_factory_configuration_errors_are_preserved(self):
        registry = ProviderRegistry()

        def incomplete(settings: Mapping[str, str]):
            raise ProviderConfigurationError("missing settings")

        registry.register("stub", incomplete)
        with self.assertRaises(ProviderConfigurationError):
            registry.create("stub", {})


if __name__ == "__main__":
    unittest.main()
