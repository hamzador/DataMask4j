"""Report known Maven dependency advisories via OSV, not a source-code or container scan."""
import argparse
import json
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError
import time

URL = "https://api.osv.dev/v1/querybatch"
OWN_GROUP = "io.github.hamzador"


def components(bom):
    packages = set()
    for component in bom.get("components", []):
        group, name, version = (component.get(k) for k in ("group", "name", "version"))
        if not group or not name or not version:
            raise ValueError("SBOM component is missing Maven coordinates")
        if group == OWN_GROUP and name.startswith("datamask4j-"):
            continue
        packages.add((f"{group}:{name}", version))
    if not packages:
        raise ValueError("No external dependencies found in SBOM")
    return sorted(packages)


def query_osv(queries):
    request = Request(URL, data=json.dumps({"queries": queries}).encode(),
                      headers={"Content-Type": "application/json", "User-Agent": "DataMask4j-CI"})
    for attempt in range(3):
        try:
            with urlopen(request, timeout=45) as response:
                result = json.load(response)
            if len(result.get("results", [])) != len(queries):
                raise ValueError("Unexpected OSV response length")
            return result["results"]
        except (OSError, TimeoutError) as ex:
            if attempt == 2 or isinstance(ex, HTTPError) and ex.code not in (429, 500, 502, 503, 504):
                raise
            time.sleep(2 ** attempt)


def audit(packages, query=query_osv):
    findings = []
    for start in range(0, len(packages), 100):
        batch = packages[start:start + 100]
        pending = [(name, version, None) for name, version in batch]
        observed = set()
        for _ in range(50):
            if not pending:
                break
            queries = []
            for name, version, token in pending:
                item = {"package": {"name": name, "ecosystem": "Maven"}, "version": version}
                if token:
                    item["page_token"] = token
                queries.append(item)
            next_page = []
            results = query(queries)
            if len(results) != len(pending):
                raise ValueError("Incomplete OSV response")
            for (name, version, _), result in zip(pending, results):
                for advisory in result.get("vulns", []):
                    observed.add((name, version, advisory["id"]))
                if result.get("next_page_token"):
                    next_page.append((name, version, result["next_page_token"]))
            pending = next_page
        if pending:
            raise ValueError("OSV pagination did not finish")
        findings.extend({"package": n, "version": v, "advisory": i} for n, v, i in sorted(observed))
    return findings


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("sbom", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--report-only", action="store_true",
                        help="Report findings without failing CI; release sign-off still required")
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    try:
        packages = components(json.loads(args.sbom.read_text()))
        findings = audit(packages)
        report = {"status": "findings" if findings else "clean", "packages_scanned": len(packages),
                  "findings_count": len(findings), "findings": findings}
    except Exception as ex:
        args.output.write_text(json.dumps({"status": "error", "error": str(ex)}, indent=2) + "\n")
        raise
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(f"OSV audit: {report['packages_scanned']} Maven coordinates scanned; {report['findings_count']} advisory matches")
    for finding in findings:
        print(f"::warning::{finding['package']} {finding['version']} — {finding['advisory']}")
    return int(bool(findings) and not args.report_only)


if __name__ == "__main__":
    raise SystemExit(main())
