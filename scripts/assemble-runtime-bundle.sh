#!/usr/bin/env bash
set -Eeuo pipefail
QEMU_PACKAGES="${1:?usage: assemble-runtime-bundle.sh QEMU_DEB_DIR ALPINE_ASSETS_DIR OUTPUT_DIR}"
ALPINE_ASSETS="${2:?missing Alpine assets directory}"
OUT="${3:?missing output directory}"
WORK="${RUNNER_TEMP:-/tmp}/miracle-runtime-bundle-$$"
PKGROOT="$WORK/packages"
BUNDLE="$WORK/bundle"
mkdir -p "$PKGROOT" "$BUNDLE/bin" "$BUNDLE/lib" "$BUNDLE/boot" "$BUNDLE/disk" "$BUNDLE/licenses" "$OUT"
cleanup(){ rm -rf "$WORK"; }
trap cleanup EXIT
for command in dpkg-deb readelf patchelf python3 sha256sum; do
  command -v "$command" >/dev/null || { echo "Missing tool: $command" >&2; exit 2; }
done
shopt -s nullglob
packages=("$QEMU_PACKAGES"/*.deb)
[ "${#packages[@]}" -gt 0 ] || { echo "No QEMU dependency .deb packages found in $QEMU_PACKAGES" >&2; exit 1; }
for package in "${packages[@]}"; do dpkg-deb -x "$package" "$PKGROOT"; done
PREFIX="$(find "$PKGROOT" -type d -path '*/data/data/com.termux/files/usr' -print -quit)"
[ -n "$PREFIX" ] || { echo "Termux package prefix not found in extracted packages" >&2; exit 1; }
QEMU="$(find "$PREFIX" -type f -name qemu-system-aarch64 -print -quit)"
[ -n "$QEMU" ] || { echo "qemu-system-aarch64 executable not found in extracted packages" >&2; exit 1; }
install -m 0755 "$QEMU" "$BUNDLE/bin/qemu-system-aarch64"
LIBDIR="$QEMU_PACKAGES/termux-lib"
if [ ! -d "$LIBDIR" ]; then
  LIBDIR="$PREFIX/share/miracle-klipper/lib"
  [ -d "$LIBDIR" ] || LIBDIR="$PREFIX/lib"
fi
[ -d "$LIBDIR" ] || { echo "Termux library directory missing: $LIBDIR" >&2; exit 1; }
copied_libraries=0
while IFS= read -r -d '' lib; do
  # Termux may ship linker-script text files with .so names. Android's
  # dynamic loader needs ELF shared objects; patchelf must never process text.
  if ! readelf -h "$lib" >/dev/null 2>&1; then
    echo "Skipping non-ELF shared-library entry: $(basename "$lib")"
    continue
  fi
  name="$(basename "$lib")"
  cp -L "$lib" "$BUNDLE/lib/$name"
  copied_libraries=$((copied_libraries + 1))
done < <(find "$LIBDIR" -maxdepth 1 \( -type f -o -type l \) \( -name '*.so' -o -name '*.so.*' \) -print0)

[ "$copied_libraries" -gt 0 ] || { echo "No ELF shared libraries copied from Termux prefix" >&2; exit 1; }

# Convert package build-time Termux paths to relocatable paths relative to this bundle.
patchelf --set-rpath '$ORIGIN/../lib' "$BUNDLE/bin/qemu-system-aarch64"
while IFS= read -r -d '' lib; do
  readelf -h "$lib" >/dev/null 2>&1 || { echo "Non-ELF file unexpectedly staged in lib/: $lib" >&2; exit 1; }
  patchelf --set-rpath '$ORIGIN' "$lib"
done < <(find "$BUNDLE/lib" -type f -name '*.so*' -print0)


LICENSE_SOURCE="$PREFIX/share/miracle-klipper/licenses"
[ -d "$LICENSE_SOURCE" ] || LICENSE_SOURCE="$PREFIX/share/doc"
if [ -d "$LICENSE_SOURCE" ]; then
  while IFS= read -r -d "" doc; do
    rel="${doc#"$LICENSE_SOURCE/"}"
    mkdir -p "$BUNDLE/licenses/$(dirname "$rel")"
    cp -L "$doc" "$BUNDLE/licenses/$rel"
  done < <(find "$LICENSE_SOURCE" \( -type f -o -type l \) -print0)
fi

# Validate the ELF interpreter, architecture, relocatable paths and recursive dependency closure.
readelf -h "$BUNDLE/bin/qemu-system-aarch64" | grep -Eq 'Machine:.*AArch64'
readelf -l "$BUNDLE/bin/qemu-system-aarch64" | grep -Fq '/system/bin/linker64'
if readelf -d "$BUNDLE/bin/qemu-system-aarch64" | grep -Eq 'Library (runpath|rpath): \[/data/data/com\.termux/'; then
  echo "Termux absolute RUNPATH remains after relocation" >&2; exit 1
fi
python3 - "$BUNDLE" <<'PY'
from pathlib import Path
import re, subprocess, sys
root=Path(sys.argv[1])
libdir=root/"lib"
system={
 "libc.so","libm.so","libdl.so","liblog.so","libandroid.so","libz.so",
 "libEGL.so","libGLESv2.so",
 "libOpenSLES.so","libvulkan.so","libnativewindow.so","libjnigraphics.so",
 "libmediandk.so","libsync.so","libbinder_ndk.so"
}
queue=[root/"bin/qemu-system-aarch64"]
seen=set()
missing=set()
while queue:
    elf=queue.pop()
    if elf in seen: continue
    seen.add(elf)
    try:
        out=subprocess.check_output(["readelf","-d",str(elf)],text=True,stderr=subprocess.DEVNULL)
    except subprocess.CalledProcessError:
        continue
    for name in re.findall(r"Shared library: \[(.*?)\]",out):
        candidate=libdir/name
        if candidate.exists():
            queue.append(candidate.resolve())
        elif name not in system:
            missing.add(name)
if missing:
    print("Unresolved Android runtime dependencies: "+", ".join(sorted(missing)),file=sys.stderr)
    raise SystemExit(1)
print(f"Resolved ELF dependency closure for {len(seen)} binaries/libraries.")
PY

cp "$ALPINE_ASSETS/boot/Image" "$BUNDLE/boot/Image"
cp "$ALPINE_ASSETS/boot/initramfs" "$BUNDLE/boot/initramfs"
cp "$ALPINE_ASSETS/disk/rootfs.qcow2" "$BUNDLE/disk/rootfs.qcow2"
[ -s "$BUNDLE/boot/Image" ] && [ -s "$BUNDLE/boot/initramfs" ] && [ -s "$BUNDLE/disk/rootfs.qcow2" ]

python3 - "$BUNDLE" <<'PY'
from pathlib import Path
import hashlib,json,sys
root=Path(sys.argv[1])
files={}
for path in sorted(root.rglob("*")):
    if path.is_file() and path.name!="manifest.json":
        rel=path.relative_to(root).as_posix()
        h=hashlib.sha256()
        with path.open("rb") as stream:
            for block in iter(lambda:stream.read(1024*1024),b""): h.update(block)
        files[rel]=h.hexdigest()
manifest={
 "schemaVersion":1,
 "architecture":"aarch64",
 "qemuBinary":"bin/qemu-system-aarch64",
 "kernel":"boot/Image",
 "initrd":"boot/initramfs",
 "diskImage":"disk/rootfs.qcow2",
 "diskFormat":"qcow2",
 "diskSizeMb":3072,
 "memoryMb":1536,
 "cpuCount":2,
 "kernelAppend":"console=ttyAMA0,115200 root=/dev/vda rootfstype=ext4 rootwait rw",
 "files":files
}
(root/"manifest.json").write_text(json.dumps(manifest,indent=2)+"\n")
print(f"Manifest contains {len(files)} SHA-256 entries.")
PY
ARCHIVE="$OUT/miracle-klipper-runtime-alpine-3.24.3-arm64.zip"
rm -f "$ARCHIVE" "$ARCHIVE.sha256"
# Build the archive with Python's streaming ZIP writer. The QCOW2 image is
# already compressed, so store it without recompressing hundreds of MB of data.
# This avoids Info-ZIP exit 15 / runner termination on large guest images.
python3 - "$BUNDLE" "$ARCHIVE" <<'PY'
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED, ZIP_STORED
import sys

root = Path(sys.argv[1])
archive = Path(sys.argv[2])
files = sorted(p for p in root.rglob("*") if p.is_file())
if not files:
    raise SystemExit("Runtime bundle is empty; refusing to create archive")

with ZipFile(archive, "w", allowZip64=True) as zf:
    for path in files:
        relative = path.relative_to(root).as_posix()
        compression = ZIP_STORED if relative == "disk/rootfs.qcow2" else ZIP_DEFLATED
        print(f"Adding {relative} ({path.stat().st_size} bytes)", flush=True)
        zf.write(path, relative, compress_type=compression, compresslevel=1 if compression == ZIP_DEFLATED else None)

with ZipFile(archive, "r") as zf:
    bad = zf.testzip()
    if bad:
        raise SystemExit(f"ZIP integrity check failed at: {bad}")
    names = set(zf.namelist())
    required = {
        "bin/qemu-system-aarch64",
        "boot/Image",
        "boot/initramfs",
        "disk/rootfs.qcow2",
        "manifest.json",
    }
    missing = sorted(required - names)
    if missing:
        raise SystemExit("Runtime archive is missing required files: " + ", ".join(missing))
    print(f"Runtime ZIP verified: {len(names)} files", flush=True)
PY
sha256sum "$ARCHIVE" > "$ARCHIVE.sha256"
echo "Runtime bundle: $ARCHIVE"
du -h "$ARCHIVE"
