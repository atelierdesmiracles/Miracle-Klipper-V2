#!/usr/bin/env bash
set -Eeuo pipefail
ASSETS="${1:?usage: smoke-test-alpine-guest.sh ASSET_DIRECTORY}"
QEMU="${QEMU_SYSTEM_AARCH64:-qemu-system-aarch64}"
LOG="${2:-$ASSETS/alpine-boot-smoke.log}"
for file in "$ASSETS/boot/Image" "$ASSETS/boot/initramfs" "$ASSETS/disk/rootfs.qcow2"; do
  [ -s "$file" ] || { echo "Missing guest asset: $file" >&2; exit 2; }
done
command -v "$QEMU" >/dev/null || { echo "Host qemu-system-aarch64 is required" >&2; exit 2; }
python3 - "$ASSETS" "$LOG" "$QEMU" <<'PY'
import os
import sys
import pexpect

assets, log_path, qemu = sys.argv[1:]
command = [
    qemu,
    "-machine", "virt,acpi=on",
    "-cpu", "cortex-a57",
    "-m", "1024",
    "-smp", "2",
    "-snapshot",
    "-kernel", os.path.join(assets, "boot", "Image"),
    "-initrd", os.path.join(assets, "boot", "initramfs"),
    "-append", "console=ttyAMA0,115200 root=/dev/vda rootfstype=ext4 rootwait rw init=/bin/sh",
    "-drive", "if=none,id=rootdisk,format=qcow2,file=" + os.path.join(assets, "disk", "rootfs.qcow2"),
    "-device", "virtio-blk-device,drive=rootdisk",
    "-netdev", "user,id=net0",
    "-device", "virtio-net-device,netdev=net0",
    "-chardev", "socket,id=usbserial,host=127.0.0.1,port=8023,server=off,reconnect=5",
    "-device", "virtio-serial-device,id=virtio-serial0",
    "-device", "virtserialport,bus=virtio-serial0.0,chardev=usbserial,name=org.miracle.usbserial",
    "-display", "none",
    "-monitor", "none",
    "-serial", "stdio",
    "-no-reboot",
]
child = None
try:
    with open(log_path, "w", buffering=1) as log:
        child = pexpect.spawn(command[0], command[1:], encoding="utf-8", timeout=75)
        child.logfile = log
        child.expect(r"#\s*$", timeout=75)
        child.setecho(False)
        child.sendline("mount -t devtmpfs devtmpfs /dev 2>/dev/null || true")
        child.sendline("modprobe virtio_console 2>/dev/null || true")
        child.sendline("sleep 1")
        child.sendline("test -e /dev/virtio-ports/org.miracle.usbserial && echo MIRACLE_VIRTIO_SERIAL_OK")
        child.sendline("echo MIRACLE_ALPINE_ROOTFS_BOOT_OK")
        child.expect("MIRACLE_VIRTIO_SERIAL_OK", timeout=15)
        child.expect("MIRACLE_ALPINE_ROOTFS_BOOT_OK", timeout=15)
    print("Alpine ARM64 kernel, initramfs, QCOW2 root filesystem and virtio serial boot passed.")
except Exception as error:
    print("Alpine guest boot smoke test failed: " + str(error), file=sys.stderr)
    try:
        if child is not None:
            print(child.before, file=sys.stderr)
    except Exception:
        pass
    raise
finally:
    if child is not None:
        try:
            child.terminate(force=True)
        except Exception:
            pass
PY
