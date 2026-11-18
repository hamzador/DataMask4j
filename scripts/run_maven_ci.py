"""Trace Maven plugin realms and initially downloaded JARs without archiving debug logs.

CI only. Preserve Maven exit status and keep all existing OSV gates unchanged.
"""
import json
import os
from pathlib import Path
import re
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
COORDINATE = re.compile(r"^[\w.\-]+:[\w.\-]+(?::[\w.\-]+){1,4}$")
ANSI = re.compile(r"\x1b\[[0-9;]*m")


def parse_realm_line(line, realm):
    clean = ANSI.sub("", line).strip()
    prefix = "[DEBUG] Populating class realm plugin>"
    if clean.startswith(prefix):
        candidate = clean[len(prefix):].strip()
        return (candidate if COORDINATE.fullmatch(candidate) else None), None
    if clean.startswith("[DEBUG] Populating class realm "):
        return None, None
    included = "[DEBUG]   Included: "
    if realm and clean.startswith(included):
        coordinate = clean[len(included):].strip()
        if COORDINATE.fullmatch(coordinate):
            return realm, coordinate
    return realm, None


def jar_paths(repository):
    return {p.relative_to(repository).as_posix() for p in repository.rglob("*.jar")}


def main():
    temp = Path(os.environ["RUNNER_TEMP"])
    repository = temp / "datamask4j-m2"
    before = jar_paths(repository)
    realms = {}
    realm = None
    # Only valid Maven coordinates are saved from debug output, never raw debug
    # configuration, environment, properties, repository credentials or URLs.
    with subprocess.Popen(
        ["mvn", "-X", "-Dstyle.color=never", *sys.argv[1:]],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
        encoding="utf-8", errors="replace", bufsize=1, cwd=ROOT,
    ) as process:
        for line in process.stdout:
            clean = ANSI.sub("", line)
            realm, coordinate = parse_realm_line(clean, realm)
            if coordinate:
                realms.setdefault(realm, set()).add(coordinate)
            if not clean.startswith("[DEBUG]"):
                print(clean, end="", flush=True)
        result = process.wait()
    with (temp / "datamask4j-maven-provenance.jsonl").open("a", encoding="utf-8") as report:
        report.write(json.dumps({
            "step": os.environ.get("GITHUB_ACTION", "local"),
            "plugin_realms": {k: sorted(v) for k, v in sorted(realms.items())},
            "new_jars": sorted(jar_paths(repository) - before),
            "exit_code": result,
        }) + "\n")
    return result


if __name__ == "__main__":
    raise SystemExit(main())
