from __future__ import annotations

from collections.abc import Callable, Mapping
from typing import TYPE_CHECKING, Protocol

if TYPE_CHECKING:
    from .server import TextEngine


class EngineFactory(Protocol):
    def __call__(self, settings: Mapping[str, str]) -> TextEngine: ...


class UnknownProviderError(ValueError):
    """Raised when configuration names a provider that is not registered."""


class ProviderConfigurationError(ValueError):
    """Raised when a registered provider is missing required configuration."""


class ProviderRegistry:
    """Explicit provider registry. No provider is selected implicitly."""

    def __init__(self) -> None:
        self._factories: dict[str, EngineFactory] = {}

    def register(self, name: str, factory: EngineFactory) -> None:
        normalized = name.strip().lower()
        if not normalized or not normalized.replace("_", "").isalnum():
            raise ValueError("Provider name is invalid.")
        if normalized in self._factories:
            raise ValueError("Provider is already registered.")
        self._factories[normalized] = factory

    def create(self, name: str, settings: Mapping[str, str]) -> TextEngine:
        normalized = name.strip().lower()
        factory = self._factories.get(normalized)
        if factory is None:
            raise UnknownProviderError("Selected text provider is not registered.")
        return factory(settings)

    def names(self) -> tuple[str, ...]:
        return tuple(sorted(self._factories))


def default_provider_registry() -> ProviderRegistry:
    registry = ProviderRegistry()

    def create_ollama(settings: Mapping[str, str]) -> TextEngine:
        base_url = settings.get("NOID_OLLAMA_BASE_URL", "").strip()
        model = settings.get("NOID_OLLAMA_MODEL", "").strip()
        if not base_url or not model:
            raise ProviderConfigurationError("Ollama URL and model must both be configured.")
        from .ollama import OllamaTextEngine
        return OllamaTextEngine(base_url, model)

    def create_local_openai(settings: Mapping[str, str]) -> TextEngine:
        base_url = settings.get("NOID_LOCAL_OPENAI_BASE_URL", "").strip()
        model = settings.get("NOID_LOCAL_OPENAI_MODEL", "").strip()
        if not base_url or not model:
            raise ProviderConfigurationError(
                "Local OpenAI-compatible URL and model must both be configured."
            )
        from .local_openai import LocalOpenAICompatibleEngine
        return LocalOpenAICompatibleEngine(base_url, model)

    registry.register("ollama", create_ollama)
    registry.register("local_openai", create_local_openai)
    return registry
