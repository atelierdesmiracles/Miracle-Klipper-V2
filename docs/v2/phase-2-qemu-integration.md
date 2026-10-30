# Phase 2 — QEMU integration boundary

## Implemented

- Deterministic QEMU command builder for ARM64 `virt` with TCG and VirtIO.
- Process start/stop adapter with preflight validation and separate process/serial logs.
- Sparse raw disk provisioning in app-private storage. Existing disks are never truncated or resized.
- Local Unix-socket QMP client: greeting validation, capability negotiation, command execution, event skipping, and error handling.
- Unit tests for QEMU command generation, missing artifacts, resource bounds, unsafe paths, and persistent disk provisioning.

## Not yet implemented or proven

1. QEMU `qemu-system-aarch64` compiled for Android Bionic `arm64-v8a`, with dependent libraries and notices.
2. Pinned Alpine ARM64 kernel, initramfs and root filesystem for QEMU `virt`.
3. Verified QEMU build support for raw block and VirtIO devices.
4. Android foreground service and UI integration for VM lifecycle.
5. Guest boot protocol/readiness signal and physical-device boot proof.
6. Docker Engine, Klipper, Moonraker, Mainsail and USB serial integration.

The QMP client and disk manager are infrastructure only. They do not prove QEMU is present or that a guest can boot.

## Validation

```bash
gradle -p v2/android testDebugUnitTest
gradle -p v2/android assembleDebug
```

Acceptance remains a successful Alpine readiness handshake on a physical ARM64 Android phone, persistence across app restart, and clean shutdown without filesystem corruption.
