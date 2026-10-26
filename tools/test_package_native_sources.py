import hashlib
import json
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path

from package_native_sources import package


class NativeSourcesTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / "source"
        self.root.mkdir()
        self.git("init", "-q")
        self.git("config", "user.name", "Source fixture")
        self.git("config", "user.email", "source@example.invalid")
        (self.root / "tracked.c").write_text("old source\n")
        self.git("add", "tracked.c")
        self.git("commit", "-qm", "Initial source")

    def git(self, *arguments):
        return subprocess.check_output(["git", "-C", str(self.root), *arguments])

    def test_local_archive_contains_current_changes_new_files_and_hashes(self):
        (self.root / "tracked.c").write_text("actual compiled source\n")
        (self.root / "src").mkdir()
        (self.root / "src/new.c").write_text("new compiled source\n")
        (self.root / "unrelated-local-file").write_text("local data\n")
        output = self.root.parent / "sources.zip"
        package(self.root, output)
        with zipfile.ZipFile(output) as archive:
            manifest = json.loads(archive.read("SOURCE-REVISIONS.json"))
            self.assertTrue(manifest["repositories"]["."]["modified"])
            self.assertNotIn("unrelated-local-file", archive.namelist())
            for name in ("tracked.c", "src/new.c"):
                data = (self.root / name).read_bytes()
                self.assertEqual(data, archive.read(name))
                self.assertEqual(hashlib.sha256(data).hexdigest(), manifest["sha256"][name])

    def test_release_archive_rejects_uncommitted_sources(self):
        (self.root / "tracked.c").write_text("changed\n")
        with self.assertRaisesRegex(ValueError, "clean and committed"):
            package(self.root, self.root.parent / "release.zip", require_clean=True)

    def test_missing_submodule_cannot_silently_archive_the_parent_repository(self):
        (self.root / ".gitmodules").write_text(
            '[submodule "native"]\npath = native\nurl = https://example.invalid/native.git\n')
        (self.root / "native").mkdir()
        with self.assertRaisesRegex(ValueError, "not initialized"):
            package(self.root, self.root.parent / "sources.zip")


if __name__ == "__main__":
    unittest.main(verbosity=2)
