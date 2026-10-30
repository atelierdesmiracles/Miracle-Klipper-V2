# Miracle Klipper V2 — architecture and integration boundaries

## Target architecture

```text
Android app (arm64-v8a, non-root)
  ├─ device diagnostics and runtime importer
  ├─ foreground VM lifecycle service
  ├─ QEMU process adapter + private QMP socket
  ├─ persistent guest disk and log capture
  ├─ local port-forward endpoints
  └─ Mainsail WebView
          │
          │ QEMU TCG / ARM virt / VirtIO
          ▼
Alpine Linux ARM64 guest
  ├─ linux-virt kernel + initramfs
  ├─ ext4 root filesystem on persistent QCOW2
  ├─ OpenRC + DHCP on virtio-net
  ├─ Klipper + Moonraker (native Python processes)
  └─ nginx + Mainsail static assets
```

## Android host responsibilities

- Restrict the first V2 build to `arm64-v8a`.
- Check Android API, ABI, memory, storage and USB Host capability.
- Import runtime ZIP files into app-private storage using staged extraction.
- Reject path traversal, oversized archives, invalid manifests and SHA-256 mismatches.
- Verify the QEMU executable is ELF64, little-endian AArch64 and matches its expected digest.
- Run QEMU in an Android foreground service while the VM is active.
- Keep QMP on an app-private Unix socket.
- Forward guest Mainsail port 8080 and Moonraker port 7125 to Android loopback only.
- Use a WebView network security policy that permits cleartext only for loopback.
- Preserve the writable guest QCOW2 disk when the runtime bundle is upgraded.
- Record QEMU process and guest serial logs in app-private storage.

The SHA-256 manifest detects file corruption or a payload that does not match its manifest. A self-contained manifest does not authenticate who published the archive; runtime bundles must come from the project build pipeline until signed-manifest verification is implemented.

## Alpine guest responsibilities

- Boot with the pinned Alpine ARM64 `linux-virt` kernel and generated initramfs.
- Mount the persistent ext4 root filesystem from `/dev/vda`.
- Bring up `eth0` using DHCP under QEMU user networking.
- Use OpenRC for boot and service lifecycle.
- Install Klipper and Moonraker at first boot using pinned source revisions and separate Python virtual environments.
- Keep `printer.cfg`, G-code, logs and communication sockets under `/var/lib/miracle-klipper`.
- Serve Mainsail through nginx and proxy Moonraker HTTP/WebSocket paths.
- Keep a hardware-neutral printer configuration until the user supplies MCU, kinematics, motion and heater safety settings.

## Network contract

| Host endpoint | QEMU forwarding | Guest service |
|---|---|---|
| `127.0.0.1:8080` | TCP hostfwd | nginx/Mainsail on guest port 8080 |
| `127.0.0.1:7125` | TCP hostfwd | Moonraker on guest port 7125 |
| `127.0.0.1:<ephemeral>` | QEMU socket chardev | virtio-serial → socat → `/dev/ttyUSB0` |

The QEMU hostfwd listener is bound to Android loopback. Do not change it to `0.0.0.0` without an explicit authentication and security review. Moonraker trusts the QEMU user-network source subnet and must not be exposed directly to an untrusted LAN.

## Klipper and Moonraker lifecycle

The guest OpenRC first-boot service installs the pinned stack if it is not already present, then starts nginx and Moonraker. Klipper starts only when `printer.cfg` contains the required hardware sections. The Android service polls Moonraker `/server/info` and reports whether Klipper is ready or disconnected; process creation alone is not a health check.

## USB and physical printer boundary

Android USB Host remains the permission authority. A serial bridge is now implemented in code: Android uses the usb-serial-for-android drivers for CDC/ACM, FTDI, CP210x and CH340 devices; it requests USB permission, opens the selected serial port, and bridges bytes through a loopback TCP socket on 127.0.0.1:<port éphémère>. QEMU connects that socket to a virtio-serial port named org.miracle.usbserial. Alpine uses socat to expose the virtio port as /dev/ttyUSB0 for Klipper. This is a serial bridge, not raw USB passthrough. It still requires CI compilation and physical-device testing.

VID/PID alone is not a unique device identity. Acceptance requires real CDC ACM, CH340, CP210x and FTDI tests, including disconnect/reconnect and permission revocation.

## Docker and optional features

Docker is deferred. It may only be enabled after the guest kernel and filesystem pass tests for namespaces, cgroups, OverlayFS, veth/bridge, netfilter and storage-driver behavior. KlipperScreen, XServer-XSDL, camera/timelapse and multi-printer process supervision are not part of the validated base runtime yet.

## Security and recovery

- No Android root requirement.
- No public QMP listener.
- No public Moonraker listener by default.
- Loopback-only host port forwards.
- Strict archive extraction and checksum checks.
- No credentials or API keys committed to source control.
- Persistent guest disk must not be replaced during runtime updates.
- Backup/restore, disk-full handling and interrupted-update recovery remain release gates.

## Performance and release gates

QEMU TCG may be slow on mobile devices. Measure boot time, CPU/RAM use, serial latency, print stability, thermals and battery drain on physical ARM64 phones. Release requires a real printer MCU connection, emergency-stop verification, thermal safety validation and a supervised test print.
