#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
LOCK="$ROOT/v2/runtime/qemu/qemu-source.lock"
CACHE="${RUNNER_TEMP:-/tmp}/miracle-klipper-qemu-source"
mkdir -p "$CACHE"

readarray -t META < <(python3 - "$LOCK" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as stream:
    lock = json.load(stream)
print(lock["source"])
print(lock["sha256"])
print(lock["version"])
PY
)
URL="${META[0]}"
EXPECTED="${META[1]}"
VERSION="${META[2]}"
ARCHIVE="$CACHE/qemu-$VERSION.tar.xz"

curl --fail --location --retry 3 --retry-delay 2 "$URL" --output "$ARCHIVE"
printf '%s  %s\n' "$EXPECTED" "$ARCHIVE" | sha256sum --check --status
echo "Verified QEMU $VERSION source archive SHA-256: $EXPECTED"
