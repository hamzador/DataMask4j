"""Validate complete XML quality reports; fail if a measured baseline regresses."""
import argparse
from pathlib import Path
import xml.etree.ElementTree as ET

MODULES = {
    "annotations": 98, "cli": 94, "core": 100, "crypto": 94,
    "faker": 100, "file": 98, "jdbc": 35, "spring-batch": 100,
    "spring-boot-starter": 100,
}
MUTATION = {"core": 100, "crypto": 68}


def check_coverage(integration=False):
    modules = {"jdbc": 100} if integration else MODULES
    failed = []
    for module, minimum in modules.items():
        path = Path(f"datamask4j-{module}/target/site/jacoco/jacoco.xml")
        root = ET.parse(path).getroot()
        counter = root.find("counter[@type='LINE']")
        covered, missed = int(counter.get("covered")), int(counter.get("missed"))
        total = covered + missed
        if not total:
            raise ValueError(f"No measured lines: {module}")
        print(f"{module}: {covered}/{total} lines ({100 * covered / total:.2f}%), minimum {minimum}%")
        if 100 * covered < minimum * total:
            failed.append(module)
    return failed


def check_mutation():
    failed = []
    for module, minimum in MUTATION.items():
        path = Path(f"datamask4j-{module}/target/pit-reports/mutations.xml")
        mutations = ET.parse(path).getroot().findall("mutation")
        if not mutations:
            raise ValueError(f"No mutations: {module}")
        killed = sum(m.get("status") == "KILLED" for m in mutations)
        print(f"{module}: {killed}/{len(mutations)} mutants killed ({100 * killed / len(mutations):.2f}%), minimum {minimum}%")
        if 100 * killed < minimum * len(mutations):
            failed.append(module)
    return failed


def check_spotbugs():
    failed = []
    for module in MODULES:
        path = Path(f"datamask4j-{module}/target/spotbugsXml.xml")
        root = ET.parse(path).getroot()
        bugs = root.findall("BugInstance")
        errors = root.findall(".//Error")
        missing = root.findall(".//MissingClass")
        print(f"{module}: {len(bugs)} findings, {len(errors)} analysis errors, {len(missing)} missing classes")
        for bug in bugs:
            print(f"  {bug.get('type')} {bug.get('priority')}")
        if bugs or errors or missing:
            failed.append(module)
        # Incomplete classpaths prevent a trustworthy zero-finding result.
    return failed


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("coverage", "integration", "mutation", "spotbugs"))
    mode = parser.parse_args().mode
    checks = {"coverage": check_coverage, "integration": lambda: check_coverage(True),
              "mutation": check_mutation, "spotbugs": check_spotbugs}
    failed = checks[mode]()
    if failed:
        print("Quality gate failed:", ", ".join(failed))
    return int(bool(failed))


if __name__ == "__main__":
    raise SystemExit(main())
