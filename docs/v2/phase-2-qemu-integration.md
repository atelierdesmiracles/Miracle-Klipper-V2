# Phase 2 — QEMU integration boundary

## Implemented

- Deterministic QEMU command builder for ARM64 `virt` with TCG and VirtIO.
- Process start/stop adapter with preflight validation and separate process/serial logs.
- Sparse raw disk provisioning in app-private storage. Existing disks are never truncated or resized.
- Local Unix-socket QMP client: greeting validation, capability negotiation, command execution, event skipping, and error handling.
- Unit tests for QEMU command generation, missing artifacts, resource bounds, unsafe paths, and persistent disk provisioning.

## QEMU build result — 30 October 2026

The Termux-based Android/Bionic build completed successfully on branch
`rewrite/miracle-klipper-v2`, commit `41ce19ca67270fefa883342c68a9e6a09914b951`.

- GitHub Actions run: `41059856120` — job `build-qemu` succeeded.
- Artifact: `miracle-klipper-qemu-termux-arm64-packages` (about 24.1 MiB, artifact ID `12978184863`).
- The artifact contains `qemu-system-aarch64_1_11.1.2_aarch64.deb` and eight other QEMU system packages, plus checksums and builder metadata.
- The extracted executable is ELF64 little-endian AArch64, Android interpreter `/system/bin/linker64`, Android API 24, built with NDK r30.
- The executable is about 31 MiB and has a Termux-specific RUNPATH: `/data/data/com.termux/files/usr/lib`.
- Its dynamic dependencies include GTK, SDL2, X11, SPICE, virglrenderer, PulseAudio, JACK, libslirp and other libraries. This is a broad desktop-enabled build, not the minimal headless runtime required by Miracle Klipper.

This proves that an Android/Bionic QEMU executable can be produced in CI. It does **not** prove that the binary can run from Miracle Klipper's private app directory. The Termux RUNPATH and dependency closure must be redesigned/audited before bundling. Do not copy the package directly into the APK.

The first artifact upload failed because Debian package filenames contained a colon. The workflow now normalizes colons to underscores before artifact upload; the subsequent QEMU job passed.

## Not yet implemented or proven

1. Minimal headless QEMU build with only AArch64 system emulation, TCG, ARM virt, serial, raw VirtIO block, user networking and QMP.
2. Complete Android/Bionic shared-library dependency closure, relocatable RUNPATH strategy, and third-party license notices.
3. QEMU executable smoke test from Miracle Klipper's app-private directory on a physical ARM64 phone.
4. Pinned Alpine ARM64 kernel, initramfs and root filesystem for QEMU `virt`.
5. Verified QEMU build support for raw block and VirtIO devices.
6. Android foreground service and UI integration for VM lifecycle.
7. Guest boot protocol/readiness signal and physical-device boot proof.
8. Docker Engine, Klipper, Moonraker, Mainsail and USB serial integration.

The QMP client and disk manager are infrastructure only. They do not prove QEMU is present or that a guest can boot.

## Validation

```bash
gradle -p v2/android testDebugUnitTest
gradle -p v2/android assembleDebug
```

Acceptance remains a successful Alpine readiness handshake on a physical ARM64 Android phone, persistence across app restart, and clean shutdown without filesystem corruption.
