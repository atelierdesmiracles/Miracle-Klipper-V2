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


## Headless QEMU build and package audit — 30 October 2026

A first headless-configured package was produced by GitHub Actions run
[41064142163](https://github.com/atelierdesmiracles/Miracle-Klipper-V2/actions/runs/41064142163),
commit `1b9219b8e20e522968fd1fc0384a19b4f4e92fa8`. The AArch64 Debian package
is about 3.85 MB and its executable is about 3.1 MB.

Post-build inspection found that this build is **not yet acceptable for APK
integration**:

- ELF RUNPATH still points to
  `/data/data/com.termux/files/usr/lib`, not Miracle Klipper's private library
  directory.
- Direct dependencies still include `libasound.so`, `libpulse.so` and
  `libjack.so.0`, despite the intended headless configuration.
- The package metadata lists only a small subset of the executable's direct
  shared-library requirements; the full dependency closure has not been
  packaged or verified.
- No execution test has been performed on a physical Android phone.

The current pipeline separates the pre-bundle package audit from final
runtime relocation:

- `scripts/audit-qemu-android-package.sh` verifies the raw package's Android
  Bionic interpreter, AArch64 machine type and absence of desktop/audio
  dependencies. A Termux build-prefix RUNPATH is reported as informational at
  this stage because the executable has not yet been moved into the app bundle.
- `scripts/assemble-runtime-bundle.sh` stages the executable and its shared
  libraries, patches the QEMU binary to use `$ORIGIN/../lib`, patches bundled
  libraries to use `$ORIGIN`, and recursively verifies the ELF dependency
  closure against Android's public system-library allowlist.
- The runtime ZIP is produced only if the relocation and dependency-closure
  checks pass.

This still does not prove execution on a physical Android phone. The final
runtime must pass the Alpine kernel/initramfs/root-disk boot test, the full
first-boot Klipper/Moonraker/Mainsail test under host QEMU, and then a physical
ARM64 Android execution test.


## Android application audio — print completion music

The headless QEMU guest runtime and the Android application's media playback
are separate concerns. Miracle Klipper must retain native Android audio
capability even when QEMU is built without guest audio devices or Linux audio
backends.

Required product behavior:
- Provide an Android-native music player in Miracle Klipper.
- Allow the user to select/manage local audio tracks and playback volume.
- Subscribe to Moonraker print lifecycle notifications.
- When a print reaches the completed state, play the configured completion
  track (or playlist) through Android's media/audio APIs.
- Do not require ALSA, PulseAudio, JACK, or an audio device inside the Alpine
  guest to play the completion music.
- Respect Android audio focus, user mute/volume settings, and app lifecycle.
- Do not start music for cancelled, failed, or merely paused prints.
- Keep this feature independent of QEMU's `--audio-drv-list` setting.

The Android-native player and Moonraker completion monitor are implemented in
`PrintCompletionMusic.kt` and `MoonrakerPrintMonitor.kt`. The monitor arms only
after observing a printing state and emits completion after a successful
complete state; cancelled/error states disarm it. Track selection and enable
state are stored in app preferences. The feature still requires end-to-end
validation against a running Moonraker instance and a physical print. It is
independent of QEMU's guest audio configuration.
