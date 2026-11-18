#!/usr/bin/env bash
# Only a disposable CI key; never use or retrieve a release secret.
set -euo pipefail
work="$(mktemp -d)"
cleanup() {
  gpgconf --homedir "$work/gpg" --kill all 2>/dev/null || true
  rm -rf "$work"
}
trap cleanup EXIT
mkdir -m 700 "$work/gpg"
gpg --homedir "$work/gpg" --batch --pinentry-mode loopback --passphrase '' \
  --quick-generate-key "DataMask4j CI rehearsal (NOT FOR UPLOAD) <ci-rehearsal@example.invalid>" rsa3072 sign 1d
fingerprint="$(gpg --homedir "$work/gpg" --batch --with-colons --list-secret-keys | awk -F: '$1 == "fpr" && !seen++ {print $10}')"
test -n "$fingerprint"
python3 scripts/rehearse_release.py --gpg-home "$work/gpg" --fingerprint "$fingerprint"
gpg --homedir "$work/gpg" --armor --export "$fingerprint" > target/rehearsal-public-key.asc
