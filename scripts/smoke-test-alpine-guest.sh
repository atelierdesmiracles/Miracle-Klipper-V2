#!/usr/bin/env bash
set -Eeuo pipefail
ASSETS="${1:?usage: smoke-test-alpine-guest.sh ASSET_DIRECTORY}"
QEMU="${QEMU_SYSTEM_AARCH64:-qemu-system-aarch64}"
LOG="${2:-$ASSETS/alpine-boot-smoke.log}"
for file in "$ASSETS/boot/Image" "$ASSETS/boot/initramfs" "$ASSETS/disk/rootfs.qcow2"; do
  [ -s "$file" ] || { echo "Missing guest asset: $file" >&2; exit 2; }
done
command -v "$QEMU" >/dev/null || { echo "Host qemu-system-aarch64 is required" >&2; exit 2; }
command -v timeout >/dev/null || { echo "timeout command is required" >&2; exit 2; }

# Use init=/bin/sh to prove the kernel, initramfs, virtio block and root filesystem
# boot without starting the first-boot package installation or changing the image.
set +e
printf 'echo MIRACLE_ALPINE_ROOTFS_BOOT_OK\nexit\n' | timeout 75s "$QEMU" \
  -machine virt -cpu cortex-a57 -m 1024 -smp 2 \
  -kernel "$ASSETS/boot/Image" \
  -initrd "$ASSETS/boot/initramfs" \
  -append "console=ttyAMA0,115200 root=/dev/vda rootfstype=ext4 rootwait rw init=/bin/sh" \
  -drive if=none,id=rootdisk,format=qcow2,file="$ASSETS/disk/rootfs.qcow2" \
  -device virtio-blk-device,drive=rootdisk \
  -netdev user,id=net0 \
  -device virtio-net-device,netdev=net0 \
  -display none -monitor none -serial stdio -no-reboot > "$LOG" 2>&1
qemu_status=$?
set -e
if ! grep -q "MIRACLE_ALPINE_ROOTFS_BOOT_OK" "$LOG"; then
  echo "Alpine ARM64 guest did not reach its root shell (QEMU exit: $qemu_status)." >&2
  tail -n 160 "$LOG" >&2 || true
  exit 1
fi
echo "Alpine ARM64 kernel + initramfs + virtio block + QCOW2 root filesystem booted successfully."
