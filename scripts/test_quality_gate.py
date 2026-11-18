"""Small regression tests for quality gates, using temporary synthetic XML."""
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import quality_gate as gate


class QualityGateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.previous = Path.cwd()
        os.chdir(self.temp.name)
        self.addCleanup(self.temp.cleanup)
        self.addCleanup(os.chdir, self.previous)

    def write(self, name, text):
        path = Path(name)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    @patch.object(gate, "MODULES", {"core": 90})
    def test_rejectCoverageBelowFloorWithoutRounding(self):
        self.write("datamask4j-core/target/site/jacoco/jacoco.xml",
                   '<report><counter type="LINE" covered="899" missed="101"/></report>')
        self.assertEqual(["core"], gate.check_coverage())
        self.write("datamask4j-core/target/site/jacoco/jacoco.xml",
                   '<report><counter type="LINE" covered="900" missed="100"/></report>')
        self.assertEqual([], gate.check_coverage())

    @patch.object(gate, "MUTATION", {"core": 80})
    def test_countAllNonKilledMutationsAgainstFloor(self):
        path = "datamask4j-core/target/pit-reports/mutations.xml"
        self.write(path, '<mutations><mutation status="KILLED"/><mutation status="NO_COVERAGE"/></mutations>')
        self.assertEqual(["core"], gate.check_mutation())
        self.write(path, '<mutations>' + '<mutation status="KILLED"/>' * 4
                   + '<mutation status="SURVIVED"/></mutations>')
        self.assertEqual([], gate.check_mutation())

    @patch.object(gate, "MODULES", {"core": 90})
    def test_failOnSpotbugsFindingsAndMissingReports(self):
        with self.assertRaises(FileNotFoundError):
            gate.check_spotbugs()
        path = "datamask4j-core/target/spotbugsXml.xml"
        self.write(path, '<BugCollection><BugInstance type="NP_NULL_PARAM_DEREF" priority="1"/></BugCollection>')
        self.assertEqual(["core"], gate.check_spotbugs())
        self.write(path, '<BugCollection/>')
        self.assertEqual([], gate.check_spotbugs())


if __name__ == "__main__":
    unittest.main()
