#!/usr/bin/env bash
# Full reachable history, including remote branches and tags fetched by checkout.
# No finding contents or report are uploaded; Gitleaks output is fully redacted.
set -euo pipefail
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
test "$(git rev-parse --is-shallow-repository)" = false
echo "Reachable refs: $(git for-each-ref --format='%(refname)' refs/remotes refs/heads refs/tags | wc -l)"
echo "Reachable commits: $(git rev-list --all --count)"
curl --fail --silent --show-error --location --retry 3 --max-time 120 \
  https://github.com/gitleaks/gitleaks/releases/download/v8.30.0/gitleaks_8.30.0_linux_x64.tar.gz \
  -o "$work/gitleaks.tar.gz"
echo "79a3ab579b53f71efd634f3aaf7e04a0fa0cf206b7ed434638d1547a2470a66e  $work/gitleaks.tar.gz" | sha256sum --check --status
tar -xzf "$work/gitleaks.tar.gz" -C "$work" gitleaks
"$work/gitleaks" version
"$work/gitleaks" git --log-opts="--all --full-history" --redact=100 --no-banner --exit-code 1 .
echo "Gitleaks reachable history scan passed"
