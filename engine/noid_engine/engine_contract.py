"""Shared text-engine contract primitives, independent of the HTTP server."""

MAX_OUTPUT_CHARS = 32_000


class EngineUnavailable(Exception):
    """Raised when an inference provider is unavailable or returns invalid output."""
