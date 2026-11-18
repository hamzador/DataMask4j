#!/usr/bin/env bash
# Scan the exact patched local image used by the PostgreSQL integration tests.
set -euo pipefail
image="${DATAMASK4J_POSTGRES_IMAGE:?Missing prepared PostgreSQL image}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p target/image-audit
test "$(docker image inspect --format '{{.Id}}' "$image")" = "${DATAMASK4J_POSTGRES_EXPECTED_ID:?Missing expected image identity}"
docker image inspect "$image" > target/image-audit/postgres-identity.json
curl --fail --silent --show-error --location --retry 3 --max-time 180 \
  https://github.com/aquasecurity/trivy/releases/download/v0.76.2/trivy_0.76.2_Linux-64bit.tar.gz -o "$work/trivy.tar.gz"
echo "bc0fc726e017b8927a62b2d3531d7a84ee01769daf5c99ed866dced54089fdbf  $work/trivy.tar.gz" | sha256sum --check --status
tar -xzf "$work/trivy.tar.gz" -C "$work" trivy
status=0
"$work/trivy" image --image-src docker --scanners vuln --severity HIGH,CRITICAL \
  --timeout 10m --exit-code 1 --format json --output target/image-audit/postgres.json "$image" || status=$?
if test -s target/image-audit/postgres.json; then
  python3 - <<'PY'
import json
from pathlib import Path
report = json.loads(Path("target/image-audit/postgres.json").read_text())
for result in report.get("Results", []):
    for vuln in result.get("Vulnerabilities") or []:
        print("{} {} {} {} fixed: {}".format(vuln.get("Severity"), vuln.get("VulnerabilityID"),
              vuln.get("PkgName"), vuln.get("InstalledVersion"), vuln.get("FixedVersion")))
PY
fi
exit "$status"
