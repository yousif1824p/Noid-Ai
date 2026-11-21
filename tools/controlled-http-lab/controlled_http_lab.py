"""Loopback-only dynamic checks for an intentionally vulnerable HTTP fixture."""
from __future__ import annotations

from contextlib import contextmanager
from dataclasses import dataclass
from http.client import HTTPConnection
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Thread
from typing import Iterator
from urllib.parse import parse_qs, urlencode, urlsplit


@dataclass(frozen=True)
class LabFinding:
    check_id: str
    severity: str
    title: str
    evidence: str


@dataclass(frozen=True)
class LabRunResult:
    origin: str
    checks_executed: int
    findings: tuple[LabFinding, ...]
    network_scope: str = "127.0.0.1 loopback only"


def _validated_origin(origin: str) -> tuple[str, int]:
    parsed = urlsplit(origin)
    if (
        parsed.scheme != "http"
        or parsed.hostname != "127.0.0.1"
        or parsed.port is None
        or parsed.username is not None
        or parsed.password is not None
        or parsed.path not in ("", "/")
        or parsed.query
        or parsed.fragment
    ):
        raise ValueError("lab origin must be a plain HTTP 127.0.0.1 origin with an explicit port")
    return parsed.hostname, parsed.port


def run_local_dynamic_checks(origin: str) -> LabRunResult:
    """Probe only the two fixed routes on a validated loopback origin."""
    host, port = _validated_origin(origin)
    findings: list[LabFinding] = []

    redirect_path = "/redirect?" + urlencode({"target": "https://example.invalid/noid-lab"})
    connection = HTTPConnection(host, port, timeout=2)
    try:
        connection.request("GET", redirect_path, headers={"Connection": "close"})
        response = connection.getresponse()
        location = response.getheader("Location", "")
        response.read(4096)
        if response.status in (301, 302, 303, 307, 308) and location.startswith("https://"):
            findings.append(
                LabFinding(
                    "external-redirect",
                    "medium",
                    "External redirect target is accepted",
                    "The local fixture returned an HTTPS Location header; the client did not follow it.",
                )
            )
    finally:
        connection.close()

    traversal_path = "/read?" + urlencode({"path": "../canary.txt"})
    connection = HTTPConnection(host, port, timeout=2)
    try:
        connection.request("GET", traversal_path, headers={"Connection": "close"})
        response = connection.getresponse()
        body = response.read(4096)
        if response.status == 200 and body:
            findings.append(
                LabFinding(
                    "path-traversal",
                    "high",
                    "Path traversal reads outside the fixture root",
                    "A fixed traversal probe returned content from the fixture parent directory.",
                )
            )
    finally:
        connection.close()

    return LabRunResult(origin=origin, checks_executed=2, findings=tuple(findings))


@contextmanager
def vulnerable_fixture(root: Path) -> Iterator[str]:
    """Start an intentionally vulnerable fixture bound exclusively to loopback."""
    root = root.resolve(strict=True)

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, _format: str, *_args: object) -> None:
            return

        def do_GET(self) -> None:
            parsed = urlsplit(self.path)
            params = parse_qs(parsed.query, keep_blank_values=True)
            if parsed.path == "/redirect":
                target = params.get("target", [""])[0]
                self.send_response(302)
                self.send_header("Location", target)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            if parsed.path == "/read":
                relative = params.get("path", [""])[0]
                # Deliberately vulnerable: this fixture omits root confinement.
                target = (root / relative).resolve()
                try:
                    data = target.read_bytes()
                except OSError:
                    self.send_error(404)
                    return
                self.send_response(200)
                self.send_header("Content-Type", "application/octet-stream")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
                return
            self.send_error(404)

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    server.daemon_threads = True
    thread = Thread(target=server.serve_forever, name="noid-controlled-http-lab", daemon=True)
    thread.start()
    host, port = server.server_address
    try:
        yield f"http://{host}:{port}"
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)
