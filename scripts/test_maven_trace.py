"""Regression cases for Maven trace parser. No credentials or raw debug logs saved."""
import unittest
from run_maven_ci import parse_realm_line

class MavenTraceTest(unittest.TestCase):
    def testTracksPluginRealmAndDependency(self):
        realm, included = parse_realm_line("[DEBUG] Populating class realm plugin>org.example:build:1.0", None)
        self.assertEqual("org.example:build:1.0", realm)
        self.assertIsNone(included)
        self.assertEqual((realm, "org.example:library:jar:2.0"),
                         parse_realm_line("[DEBUG]   Included: org.example:library:jar:2.0", realm))

    def testRejectsNonCoordinateDebugValues(self):
        realm = "org.example:build:1.0"
        self.assertEqual((realm, None), parse_realm_line("[DEBUG]   Included: https://example.test/private", realm))
        self.assertEqual((None, None), parse_realm_line("[DEBUG] Populating class realm extension>other", realm))
        self.assertEqual((None, None), parse_realm_line("[DEBUG] Populating class realm plugin>invalid value", realm))

if __name__ == "__main__":
    unittest.main()
