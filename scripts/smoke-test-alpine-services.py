#!/usr/bin/env python3
"""End-to-end Alpine first-boot test under host QEMU TCG."""
import json
import os
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

assets = sys.argv[1]
log_path = sys.argv[2] if len(sys.argv) > 2 else os.path.join(assets, "alpine-services-smoke.log")
qemu = os.environ.get("QEMU_SYSTEM_AARCH64", "qemu-system-aarch64")


def free_loopback_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


moonraker_port = free_loopback_port()
mainsail_port = free_loopback_port()
while mainsail_port == moonraker_port:
    mainsail_port = free_loopback_port()

serial_log = os.path.join(assets, "alpine-firstboot-serial.log")
qemu_command = [
    qemu,
    "-machine", "virt,acpi=on",
    "-cpu", "cortex-a57",
    "-m", "1536",
    "-smp", "2",
    "-snapshot",
    "-kernel", os.path.join(assets, "boot", "Image"),
    "-initrd", os.path.join(assets, "boot", "initramfs"),
    "-append", "console=ttyAMA0,115200 root=/dev/vda rootfstype=ext4 rootwait rw",
    "-drive", "if=none,id=rootdisk,format=qcow2,file=" + os.path.join(assets, "disk", "rootfs.qcow2"),
    "-device", "virtio-blk-device,drive=rootdisk",
    "-netdev", "user,id=net0,hostfwd=tcp:127.0.0.1:" + str(moonraker_port) + "-:7125,hostfwd=tcp:127.0.0.1:" + str(mainsail_port) + "-:8080",
    "-device", "virtio-net-device,netdev=net0",
    "-chardev", "socket,id=usbserial,host=127.0.0.1,port=8023,server=off,reconnect=5",
    "-device", "virtio-serial-device,id=virtio-serial0",
    "-device", "virtserialport,bus=virtio-serial0.0,chardev=usbserial,name=org.miracle.usbserial",
    "-display", "none",
    "-monitor", "none",
    "-serial", "file:" + serial_log,
    "-no-reboot",
]

opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def fetch(url, timeout=4):
    with opener.open(url, timeout=timeout) as response:
        return response.status, response.read()


process = None
deadline = time.monotonic() + 1200
try:
    with open(log_path, "w", buffering=1) as report:
        report.write("Starting Alpine full first-boot test under QEMU TCG\n")
        report.write("Moonraker host port: " + str(moonraker_port) + "\n")
        report.write("Mainsail host port: " + str(mainsail_port) + "\n")
        process = subprocess.Popen(qemu_command, stdout=subprocess.DEVNULL, stderr=report)
        moonraker_ready = False
        mainsail_ready = False
        last_error = "guest is booting"
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError("QEMU exited early with status " + str(process.returncode))
            if not moonraker_ready:
                try:
                    status, payload = fetch("http://127.0.0.1:" + str(moonraker_port) + "/server/info")
                    data = json.loads(payload.decode("utf-8"))
                    result = data.get("result", {})
                    if status == 200 and "klippy_state" in result:
                        moonraker_ready = True
                        report.write("Moonraker HTTP ready; klippy_state=" + str(result["klippy_state"]) + "\n")
                except Exception as error:
                    last_error = "Moonraker: " + str(error)
            if not mainsail_ready:
                try:
                    status, payload = fetch("http://127.0.0.1:" + str(mainsail_port) + "/")
                    if status == 200 and b"<html" in payload.lower():
                        mainsail_ready = True
                        report.write("Mainsail HTTP ready\n")
                except Exception as error:
                    last_error = "Mainsail: " + str(error)
            if moonraker_ready and mainsail_ready:
                report.write("PASS: Alpine firstboot, Klipper/Moonraker install, Moonraker HTTP and Mainsail HTTP\n")
                print("Alpine first-boot stack passed: Moonraker HTTP and Mainsail HTTP are reachable.")
                break
            time.sleep(5)
        else:
            raise TimeoutError("Guest services did not become ready within 20 minutes. " + last_error)
except Exception as error:
    print("Alpine first-boot service smoke test failed: " + str(error), file=sys.stderr)
    try:
        with open(serial_log, "r", errors="replace") as serial:
            print("".join(serial.readlines()[-160:]), file=sys.stderr)
    except OSError:
        pass
    raise
finally:
    if process is not None and process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)
