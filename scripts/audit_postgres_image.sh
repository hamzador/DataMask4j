#!/usr/bin/env bash
# Scan the pinned PostgreSQL fixture image separately from the integration runner.
set -euo pipefail
image='postgres@sha256:f377d590e5474681d4d7838635031a2b50647923b7abfa803ce9b5ff35d5c663'
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p target/image-audit
docker pull "$image"
docker image inspect "$image" > target/image-audit/postgres-identity.json
curl --fail --silent --show-error --location --retry 3 --max-time 180 \
  https://github.com/aquasecurity/trivy/releases/download/v0.76.2/trivy_0.76.2_Linux-64bit.tar.gz -o "$work/trivy.tar.gz"
echo "bc0fc726e017b8927a62b2d3531d7a84ee01769daf5c99ed866dced54089fdbf  $work/trivy.tar.gz" | sha256sum --check --status
tar -xzf "$work/trivy.tar.gz" -C "$work" trivy
"$work/trivy" image --image-src docker --scanners vuln --severity HIGH,CRITICAL \
  --timeout 10m --exit-code 1 --format json --output target/image-audit/postgres.json "$image"
