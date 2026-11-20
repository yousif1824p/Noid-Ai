import json, sys, tempfile, unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from manifest import ManifestError, create_manifest, verify_manifest
class ManifestTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.root = Path(self.tmp.name) / "release"
        (self.root / "app").mkdir(parents=True); (self.root / "app" / "NOID.apk").write_bytes(b"apk-placeholder")
        (self.root / "README.txt").write_text("private release\n", encoding="utf-8")
    def tearDown(self): self.tmp.cleanup()
    def test_repeatable_create_and_verify(self):
        p = create_manifest(self.root); first = p.read_bytes(); self.assertEqual(2, verify_manifest(self.root)); create_manifest(self.root); self.assertEqual(first, p.read_bytes())
    def test_detects_modified_file(self):
        create_manifest(self.root); (self.root / "README.txt").write_text("changed\n", encoding="utf-8")
        with self.assertRaisesRegex(ManifestError, "mismatch"): verify_manifest(self.root)
    def test_detects_added_and_removed_files(self):
        create_manifest(self.root); (self.root / "app" / "NOID.apk").unlink(); (self.root / "new.txt").write_text("extra")
        with self.assertRaisesRegex(ManifestError, "file set mismatch"): verify_manifest(self.root)
    def test_rejects_traversal(self):
        create_manifest(self.root); p = self.root / "NOID-MANIFEST.json"; d = json.loads(p.read_text()); d["files"][0]["path"] = "../outside"; p.write_text(json.dumps(d))
        with self.assertRaisesRegex(ManifestError, "unsafe"): verify_manifest(self.root)
    def test_rejects_duplicate(self):
        create_manifest(self.root); p = self.root / "NOID-MANIFEST.json"; d = json.loads(p.read_text()); d["files"].append(dict(d["files"][0])); p.write_text(json.dumps(d))
        with self.assertRaisesRegex(ManifestError, "duplicate"): verify_manifest(self.root)
    def test_rejects_symlink(self):
        create_manifest(self.root); target = self.root / "outside.txt"; target.write_text("x"); link = self.root / "linked.txt"
        try: link.symlink_to(target)
        except (OSError, NotImplementedError): self.skipTest("symlinks unavailable")
        with self.assertRaisesRegex(ManifestError, "symlink"): verify_manifest(self.root)
    def test_rejects_manifest_outside_root(self):
        with self.assertRaisesRegex(ManifestError, "inside release root"): create_manifest(self.root, str(Path(self.tmp.name) / "outside.json"))
    def test_rejects_backslash_paths(self):
        create_manifest(self.root)
        p = self.root / "NOID-MANIFEST.json"
        d = json.loads(p.read_text())
        d["files"][0]["path"] = "folder\\\\file"
        p.write_text(json.dumps(d))
        with self.assertRaisesRegex(ManifestError, "invalid relative path"):
            verify_manifest(self.root)

if __name__ == "__main__": unittest.main()
