#!/usr/bin/env bash
set -euo pipefail

PACKAGE="${1:?usage: audit-qemu-android-package.sh path/to/qemu-system-aarch64.deb}"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

command -v dpkg-deb >/dev/null || { echo "ERROR: dpkg-deb is required" >&2; exit 2; }
READELF="${READELF:-readelf}"
command -v "$READELF" >/dev/null || { echo "ERROR: readelf/llvm-readelf is required" >&2; exit 2; }

dpkg-deb -x "$PACKAGE" "$WORKDIR/root"
BIN="$(find "$WORKDIR/root" -type f -name qemu-system-aarch64 -print -quit)"
[ -n "$BIN" ] || { echo "ERROR: qemu-system-aarch64 not found in package" >&2; exit 1; }

echo "== Package =="
dpkg-deb -f "$PACKAGE" Package Version Architecture Depends
echo "== ELF =="
file "$BIN"
"$READELF" -h "$BIN" | grep -E 'Class:|Data:|Machine:'
"$READELF" -l "$BIN" | grep -F '/system/bin/linker64' || true
echo "== Dynamic section =="
"$READELF" -d "$BIN" | grep -E 'NEEDED|RPATH|RUNPATH' || true

FAIL=0
if ! "$READELF" -l "$BIN" | grep -Fq '/system/bin/linker64'; then
  echo "ERROR: Android Bionic interpreter is missing" >&2
  FAIL=1
fi
if ! "$READELF" -h "$BIN" | grep -Eq 'Machine:.*AArch64'; then
  echo "ERROR: executable is not AArch64" >&2
  FAIL=1
fi
if "$READELF" -d "$BIN" | grep -Eq 'Library (runpath|rpath): \[/data/data/com\.termux/'; then
  echo "INFO: raw Termux build-prefix RUNPATH will be replaced by the runtime assembler."
fi
if "$READELF" -d "$BIN" | grep -Eq 'Shared library: \[(libasound|libpulse|libjack|libSDL|libgtk|libX11|libspice|libvirgl)'; then
  echo "ERROR: desktop/audio dependency remains in headless QEMU" >&2
  FAIL=1
fi

if [ "$FAIL" -ne 0 ]; then
  echo "QEMU package audit FAILED: do not bundle this binary in the APK." >&2
  exit 1
fi

echo "Pre-bundle QEMU ELF audit passed. Runtime assembly must relocate RPATH and verify the complete shared-library closure; this does not prove execution on a phone."
