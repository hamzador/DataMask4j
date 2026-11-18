#!/usr/bin/env bash
# Local CI-only PostgreSQL image; never pushed. Upstream manifest digest is locked.
# Rebuild gosu from the upstream 1.20 source with a patched checksum-verified Go release.
set -euo pipefail
test "$(uname -m)" = x86_64
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
curl --fail --silent --show-error --location --retry 3 --max-time 120 \
  https://go.dev/dl/go1.27.4.linux-amd64.tar.gz -o "$work/go.tar.gz"
echo "7226736c2339719cda28fbf3a54d71aa63bcefe376e98cef00034268dab24840  $work/go.tar.gz" | sha256sum --check --status
tar -xzf "$work/go.tar.gz" -C "$work"
git init --quiet "$work/gosu"
git -C "$work/gosu" fetch --depth=1 https://github.com/tianon/gosu.git 29b939aa5bd4dc588af765b8248f6e39150d2565
git -C "$work/gosu" checkout --detach FETCH_HEAD
test "$(git -C "$work/gosu" rev-parse HEAD)" = 29b939aa5bd4dc588af765b8248f6e39150d2565
mkdir "$work/context"
(
  cd "$work/gosu"
  export GOTOOLCHAIN=local GOOS=linux GOARCH=amd64 CGO_ENABLED=0
  export GOSUMDB=sum.golang.org GOPROXY=https://proxy.golang.org
  "$work/go/bin/go" mod download
  "$work/go/bin/go" mod verify
  "$work/go/bin/go" build -mod=readonly -trimpath -o "$work/context/gosu" .
  "$work/go/bin/go" version -m "$work/context/gosu"
  git diff --exit-code -- go.mod go.sum
)
chmod 0755 "$work/context/gosu"

base='postgres@sha256:f377d590e5474681d4d7838635031a2b50647923b7abfa803ce9b5ff35d5c663'
tag='datamask4j-postgres-ci:patched'
docker pull "$base"
original_user="$(docker image inspect --format '{{.Config.User}}' "$base")"
original_user="${original_user:-0}"
docker build --no-cache --platform linux/amd64 --build-arg "BASE=$base" \
  --build-arg "ORIGINAL_USER=$original_user" --file ci/test-images/postgres.Dockerfile \
  --tag "$tag" "$work/context"
docker run --rm --user 0 --entrypoint /usr/local/bin/gosu "$tag" --version
test "$(docker run --rm --user 0 --entrypoint /usr/local/bin/gosu "$tag" nobody id -u)" != 0
image_id="$(docker image inspect --format '{{.Id}}' "$tag")"
test -n "$image_id"
echo "DATAMASK4J_POSTGRES_IMAGE=$tag" >> "${GITHUB_ENV:?GITHUB_ENV required}"
echo "DATAMASK4J_POSTGRES_EXPECTED_ID=$image_id" >> "$GITHUB_ENV"
