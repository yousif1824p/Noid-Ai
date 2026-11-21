import tempfile
import unittest
from pathlib import Path

from controlled_http_lab import run_local_dynamic_checks, vulnerable_fixture


class ControlledHttpLabTests(unittest.TestCase):
    def test_detects_both_vulnerabilities_in_local_fixture_without_following_redirect(self):
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            root = parent / "www"
            root.mkdir()
            (root / "index.txt").write_text("inside", encoding="utf-8")
            (parent / "canary.txt").write_text("fixture-canary", encoding="utf-8")

            with vulnerable_fixture(root) as origin:
                result = run_local_dynamic_checks(origin)

            self.assertEqual(2, result.checks_executed)
            self.assertEqual("127.0.0.1 loopback only", result.network_scope)
            self.assertEqual(
                {"external-redirect", "path-traversal"},
                {finding.check_id for finding in result.findings},
            )
            self.assertNotIn("fixture-canary", repr(result.findings))

    def test_rejects_non_loopback_hosts_before_opening_a_connection(self):
        for origin in (
            "http://example.com:8080",
            "http://localhost:8080",
            "http://127.0.0.2:8080",
            "https://127.0.0.1:8080",
            "http://user@127.0.0.1:8080",
            "http://127.0.0.1:8080/unexpected",
        ):
            with self.subTest(origin=origin):
                with self.assertRaises(ValueError):
                    run_local_dynamic_checks(origin)

    def test_rejects_origins_without_explicit_ports(self):
        with self.assertRaises(ValueError):
            run_local_dynamic_checks("http://127.0.0.1")


if __name__ == "__main__":
    unittest.main()
