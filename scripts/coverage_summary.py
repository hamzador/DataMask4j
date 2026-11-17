"""Print per-module JaCoCo instruction and line coverage; no fabricated aggregate."""
from pathlib import Path
import xml.etree.ElementTree as ET
reports = sorted(Path(".").glob("datamask4j-*/target/site/jacoco/jacoco.xml"))
assert len(reports) == 9, f"Expected 9 reports, found {len(reports)}"
print("| Module | Instructions | Lines |")
print("|---|---:|---:|")
for path in reports:
    counters = {node.get("type"): node for node in ET.parse(path).getroot().findall("counter")}
    values = []
    for name in ("INSTRUCTION", "LINE"):
        counter = counters[name]
        covered, missed = int(counter.get("covered")), int(counter.get("missed"))
        values.append(f"{100 * covered / (covered + missed):.1f}%" if covered + missed else "N/A")
    print(f"| {path.parts[0]} | {values[0]} | {values[1]} |")
