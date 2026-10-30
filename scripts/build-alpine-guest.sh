#!/usr/bin/env bash
set -Eeuo pipefail
ALPINE_VERSION="${ALPINE_VERSION:-3.24.3}"
ALPINE_BRANCH="${ALPINE_BRANCH:-3.24}"
DISK_SIZE_MIB="${DISK_SIZE_MIB:-3072}"
OUT="${1:-$PWD/alpine-guest-output}"
WORK="${RUNNER_TEMP:-/tmp}/miracle-alpine-$$"
ROOT="$WORK/rootfs"
ARCHIVE="$WORK/alpine-minirootfs.tar.gz"
BASE_URL="https://dl-cdn.alpinelinux.org/alpine/v$ALPINE_BRANCH"
mkdir -p "$OUT" "$WORK"
cleanup() {
  for mountpoint in "$ROOT/dev" "$ROOT/proc" "$ROOT/sys"; do
    if mountpoint -q "$mountpoint"; then umount -lf "$mountpoint" || true; fi
  done
  rm -rf "$WORK"
}
trap cleanup EXIT
for command in curl sha256sum tar chroot mount umount mkfs.ext4 qemu-img; do
  command -v "$command" >/dev/null || { echo "Missing host tool: $command" >&2; exit 2; }
done
[ "$(id -u)" -eq 0 ] || { echo "Run this builder as root (sudo)." >&2; exit 2; }
[ -x /usr/bin/qemu-aarch64-static ] || { echo "qemu-aarch64-static is required." >&2; exit 2; }
if command -v update-binfmts >/dev/null 2>&1; then update-binfmts --enable qemu-aarch64 || true; fi
curl --fail --location --retry 4 "$BASE_URL/releases/aarch64/alpine-minirootfs-$ALPINE_VERSION-aarch64.tar.gz" -o "$ARCHIVE"
curl --fail --location --retry 4 "$BASE_URL/releases/aarch64/alpine-minirootfs-$ALPINE_VERSION-aarch64.tar.gz.sha256" -o "$ARCHIVE.sha256"
EXPECTED="$(awk 'NR==1 {print $1}' "$ARCHIVE.sha256")"
printf '%s  %s\n' "$EXPECTED" "$ARCHIVE" | sha256sum -c -
mkdir -p "$ROOT"
tar -xzf "$ARCHIVE" -C "$ROOT"
# The chroot shares the runner network namespace; copy the runner resolver into the guest root.
rm -f "$ROOT/etc/resolv.conf"
cp -L /etc/resolv.conf "$ROOT/etc/resolv.conf"
MIRROR_IPS="$(getent ahostsv4 dl-cdn.alpinelinux.org | awk '{print $1}' | sort -u)"
[ -n "$MIRROR_IPS" ] || { echo "Host DNS could not resolve the Alpine mirror." >&2; exit 1; }
while IFS= read -r ip; do printf "%s dl-cdn.alpinelinux.org\n" "$ip" >> "$ROOT/etc/hosts"; done <<EOF
$MIRROR_IPS
EOF
install -D -m 0755 /usr/bin/qemu-aarch64-static "$ROOT/usr/bin/qemu-aarch64-static"
cat > "$ROOT/etc/apk/repositories" <<EOF
$BASE_URL/main
$BASE_URL/community
EOF
mkdir -p "$ROOT/dev" "$ROOT/proc" "$ROOT/sys"
mount --bind /dev "$ROOT/dev"
mount -t proc proc "$ROOT/proc"
mount -t sysfs sysfs "$ROOT/sys"
chroot "$ROOT" /bin/sh -euxc '
  apk update
  apk add --no-cache alpine-base openrc ifupdown-ng linux-virt mkinitfs kmod \
    ca-certificates curl git nginx python3 py3-pip py3-virtualenv unzip procps \
    build-base linux-headers musl-dev libffi-dev openssl-dev jpeg-dev zlib-dev e2fsprogs
  update-ca-certificates
'
# Do not bake the build runner DNS stub into the guest; DHCP supplies QEMU user-network DNS at boot.
: > "$ROOT/etc/resolv.conf"
cat > "$ROOT/etc/hostname" <<'EOF'
miracle-klipper
EOF
cat > "$ROOT/etc/network/interfaces" <<'EOF'
auto lo
iface lo inet loopback
auto eth0
iface eth0 inet dhcp
EOF
cat > "$ROOT/etc/fstab" <<'EOF'
/dev/vda / ext4 defaults,noatime 0 1
proc /proc proc defaults 0 0
sysfs /sys sysfs defaults 0 0
devtmpfs /dev devtmpfs mode=0755,nosuid 0 0
EOF
mkdir -p "$ROOT/root/miracle-klipper" "$ROOT/var/lib/miracle-klipper"
install -m 0755 "$(dirname "$0")/../v2/runtime/klipper/install-klipper-alpine.sh" "$ROOT/root/miracle-klipper/install-klipper-alpine.sh"
install -m 0755 "$(dirname "$0")/../v2/runtime/klipper/miracle-klipper-manager.sh" "$ROOT/root/miracle-klipper/miracle-klipper-manager.sh"
install -m 0755 "$(dirname "$0")/../v2/runtime/klipper/miracle-klipper-instances.sh" "$ROOT/root/miracle-klipper/miracle-klipper-instances.sh"
install -m 0755 "$(dirname "$0")/../v2/runtime/klipper/miracle-klipper-firmware.sh" "$ROOT/root/miracle-klipper/miracle-klipper-firmware.sh"
cat > "$ROOT/usr/local/sbin/miracle-klipper-firstboot" <<'EOF'
#!/bin/sh
set -eu
INSTALLER=/root/miracle-klipper/install-klipper-alpine.sh
if [ ! -x /usr/local/bin/miracle-klipper-start ] || [ ! -x /opt/miracle-klipper/venvs/moonraker/bin/python ]; then
  /bin/sh "$INSTALLER"
fi
exec /usr/local/bin/miracle-klipper-start
EOF
chmod 0755 "$ROOT/usr/local/sbin/miracle-klipper-firstboot"
cat > "$ROOT/etc/init.d/miracle-klipper" <<'EOF'
#!/sbin/openrc-run
description="Miracle Klipper printer stack"
depend() { need networking; after firewall; }
start() {
  ebegin "Starting Miracle Klipper (first boot may install Klipper and Moonraker)"
  /usr/local/sbin/miracle-klipper-firstboot
  eend $?
}
stop() {
  ebegin "Stopping Miracle Klipper"
  if [ -x /usr/local/bin/miracle-klipper-stop ]; then /usr/local/bin/miracle-klipper-stop; fi
  eend $?
}
EOF
chmod 0755 "$ROOT/etc/init.d/miracle-klipper"
chroot "$ROOT" /bin/sh -euxc '
  rc-update add devfs sysinit
  rc-update add dmesg sysinit
  rc-update add mdev sysinit
  rc-update add hwdrivers sysinit
  rc-update add modules boot
  rc-update add sysctl boot
  rc-update add hostname boot
  rc-update add bootmisc boot
  rc-update add syslog boot
  rc-update add networking boot
  rc-update add local default
  rc-update add miracle-klipper default
'
KERNEL="$(find "$ROOT/boot" -maxdepth 1 \( -type f -o -type l \) -name 'vmlinuz-virt' -print -quit)"
[ -n "$KERNEL" ] || { echo "Alpine linux-virt did not provide /boot/vmlinuz-virt" >&2; ls -la "$ROOT/boot"; exit 1; }
KVER="$(find "$ROOT/lib/modules" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort -V | tail -n1)"
[ -n "$KVER" ] || { echo "No installed Alpine kernel modules found" >&2; exit 1; }
cat > "$ROOT/etc/modules" <<EOF
virtio_mmio
virtio_console
virtio_blk
virtio_net
EOF
chroot "$ROOT" /sbin/mkinitfs -o /boot/initramfs-miracle "$KVER"
INITRD="$ROOT/boot/initramfs-miracle"
[ -s "$INITRD" ] || { echo "mkinitfs did not produce an initramfs" >&2; exit 1; }
rm -f "$ROOT/usr/bin/qemu-aarch64-static"
for mountpoint in "$ROOT/dev" "$ROOT/proc" "$ROOT/sys"; do
  if mountpoint -q "$mountpoint"; then umount -lf "$mountpoint"; fi
done
mkdir -p "$OUT/boot" "$OUT/disk"
cp -L "$KERNEL" "$OUT/boot/Image"
cp "$INITRD" "$OUT/boot/initramfs"
RAW="$WORK/alpine-rootfs.raw"
truncate -s "${DISK_SIZE_MIB}M" "$RAW"
mkfs.ext4 -F -m 0 -L MIRACLE_ROOT -d "$ROOT" "$RAW"
qemu-img convert -f raw -O qcow2 -c "$RAW" "$OUT/disk/rootfs.qcow2"
qemu-img check "$OUT/disk/rootfs.qcow2"
qemu-img info "$OUT/disk/rootfs.qcow2" > "$OUT/disk/qcow2-info.txt"
cat > "$OUT/alpine-build-info.txt" <<EOF
Alpine version: $ALPINE_VERSION
Architecture: aarch64
Kernel package: linux-virt
Kernel modules: $KVER
Root disk virtual size: $DISK_SIZE_MIB MiB
Root filesystem: ext4 inside qcow2
Boot arguments: console=ttyAMA0,115200 root=/dev/vda rootfstype=ext4 rootwait rw
EOF
echo "Alpine ARM64 guest assets built in: $OUT"
du -h "$OUT/boot/Image" "$OUT/boot/initramfs" "$OUT/disk/rootfs.qcow2"
