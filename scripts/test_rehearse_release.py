"""Rehearsal must fail closed on missing or non-snapshot POM metadata."""
import tempfile
import unittest
from pathlib import Path
from rehearse_release import inventory, require

class ReleaseRehearsalTest(unittest.TestCase):
    def testRejectsFinalReleaseVersion(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "pom.xml").write_text(
                '<project xmlns="http://maven.apache.org/POM/4.0.0"><groupId>io.github.hador</groupId>'
                '<version>0.1.0</version></project>')
            with self.assertRaisesRegex(ValueError, "authorised snapshot"):
                inventory(root)

    def testRejectsMissingModules(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "pom.xml").write_text(
                '<project xmlns="http://maven.apache.org/POM/4.0.0"><groupId>io.github.hador</groupId>'
                '<version>0.1.0-SNAPSHOT</version></project>')
            with self.assertRaisesRegex(ValueError, "nine modules"):
                inventory(root)

    def testRequireFailsClosed(self):
        require(True, "ok")
        with self.assertRaises(ValueError):
            require(False, "missing archive")
