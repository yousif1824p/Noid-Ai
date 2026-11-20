import os
import tempfile
import unittest
from pathlib import Path

from preflight import REQUIRED, validate_signing_environment


class SigningPreflightTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name) / "repo"
        self.root.mkdir()
        self.outside = Path(self.temp.name) / "private"
        self.outside.mkdir()
        self.keystore = self.outside / "release.jks"
        self.keystore.write_bytes(b"test-keystore-placeholder")
        self.env = {
            "NOID_RELEASE_STORE_FILE": str(self.keystore),
            "NOID_RELEASE_STORE_PASSWORD": "never-print-this",
            "NOID_RELEASE_KEY_ALIAS": "noid",
            "NOID_RELEASE_KEY_PASSWORD": "also-secret",
        }

    def tearDown(self):
        self.temp.cleanup()

    def test_accepts_complete_external_regular_keystore(self):
        self.assertEqual([], validate_signing_environment(self.env, self.root))

    def test_reports_missing_variable_names_without_values(self):
        env = dict(self.env)
        del env["NOID_RELEASE_KEY_PASSWORD"]
        errors = validate_signing_environment(env, self.root)
        self.assertEqual(1, len(errors))
        self.assertIn("NOID_RELEASE_KEY_PASSWORD", errors[0])
        self.assertNotIn("never-print-this", str(errors))

    def test_rejects_keystore_inside_repository(self):
        inside = self.root / "release.jks"
        inside.write_bytes(b"placeholder")
        env = dict(self.env, NOID_RELEASE_STORE_FILE=str(inside))
        self.assertIn("outside the repository", validate_signing_environment(env, self.root)[0])

    def test_rejects_missing_keystore(self):
        env = dict(self.env, NOID_RELEASE_STORE_FILE=str(self.outside / "missing.jks"))
        self.assertIn("not accessible", validate_signing_environment(env, self.root)[0])

    def test_rejects_empty_keystore(self):
        empty = self.outside / "empty.jks"
        empty.touch()
        env = dict(self.env, NOID_RELEASE_STORE_FILE=str(empty))
        self.assertIn("empty", validate_signing_environment(env, self.root)[0])

    def test_rejects_symlink_keystore(self):
        link = self.outside / "linked.jks"
        link.symlink_to(self.keystore)
        env = dict(self.env, NOID_RELEASE_STORE_FILE=str(link))
        self.assertIn("symbolic link", validate_signing_environment(env, self.root)[0])

    def test_rejects_blank_required_values(self):
        for name in REQUIRED:
            env = dict(self.env, **{name: "   "})
            self.assertTrue(validate_signing_environment(env, self.root), name)


if __name__ == "__main__":
    unittest.main()
