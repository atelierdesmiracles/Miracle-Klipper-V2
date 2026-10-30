# Miracle Klipper V2 — roadmap and acceptance criteria

This roadmap describes the current V2 implementation. A CI build is necessary but never substitutes for a physical ARM64 phone and printer test.

## Phase 0 — repository and product foundation

- [x] Keep V2 isolated on `rewrite/miracle-klipper-v2`.
- [x] Keep legacy PRoot/Debian startup out of the V2 app.
- [x] Define native Android ARM64 + QEMU TCG + Alpine ARM64 architecture.
- [x] Add a dedicated Android build/test workflow.
- [x] Document the runtime bundle contract and KIAUH adaptation.

Gate: the V2 branch remains independent of `main` and the legacy project.

## Phase 1 — Android host and runtime lifecycle

Implemented in code:
- Native Android shell and device diagnostics.
- Runtime ZIP importer with path validation, staged installation and SHA-256 payload checks.
- QEMU ELF64/AArch64 preflight verification.
- Foreground service to start/stop QEMU.
- Persistent guest disk seeding that does not overwrite user data on runtime updates.
- QMP Unix socket command construction and process logs.
- Embedded Mainsail WebView and loopback-only cleartext network policy.
- QEMU host forwarding for Android loopback ports 8080 (Mainsail) and 7125 (Moonraker).
- Moonraker readiness polling after guest start.

Still required:
- Green CI on the latest branch head.
- Install and lifecycle validation on physical Android 8+ ARM64 devices.
- Confirm Android execution policy allows the packaged Bionic QEMU executable and its relocated library closure.

Gate:
- Debug APK installs and launches.
- QEMU process starts and stops repeatedly without leaks.
- Runtime update preserves the writable guest disk.

## Phase 2 — QEMU and Alpine guest boot

Build pipeline now present:
- QEMU 11.1.2 source pin and Termux headless ARM64 build recipe.
- Packaging of shared-library closure and license notices.
- Relocatable RUNPATH and recursive ELF dependency audit.
- Alpine 3.24.3 ARM64 minirootfs build.
- Alpine `linux-virt` kernel and mkinitfs initramfs.
- ext4 root filesystem converted to persistent QCOW2 disk.
- Runtime manifest with SHA-256 entries.

Still required:
- QEMU package build and dependency closure pass in GitHub Actions.
- Alpine guest image build passes in GitHub Actions.
- Complete runtime ZIP assembled and imported by the app.
- Alpine reaches OpenRC on a physical Android ARM64 phone.
- Disk persistence and clean shutdown verified.

Gate:
- Serial boot log shows a completed Alpine boot.
- Guest networking receives DHCP under QEMU user networking.
- A restart preserves a test file on the QCOW2 disk.

## Phase 3 — Klipper, Moonraker and Mainsail

Guest first-boot scripts:
- Install pinned Klipper and Moonraker source revisions.
- Separate Python virtual environments and dependency checks.
- Keep printer configuration, G-code, logs and communication sockets under persistent guest data.
- Install pinned Mainsail release with SHA-256 check.
- Configure nginx and Moonraker WebSocket/HTTP reverse proxy.
- OpenRC service and KIAUH-inspired management commands.
- Hardware-neutral `printer.cfg`; no invented MCU, heater or motion settings.

Still required:
- Validate first-boot installer idempotence on Alpine.
- Confirm Moonraker HTTP and WebSocket APIs.
- Confirm Mainsail loads and reflects live state.
- Confirm Klipper becomes ready with a valid simulator configuration.

Gate:
- Fresh guest boot installs and starts the web/API layer.
- Mainsail and Moonraker are reachable only through Android loopback forwarding.
- Klipper service reports its actual state, not merely a launched PID.

## Phase 4 — USB serial and physical printer

Not yet complete:
- Android USB Host permission/attach/detach manager.
- Driver support for CDC ACM, CH340, CP210x and FTDI.
- QEMU USB passthrough or a reviewed Android USB-to-guest serial bridge.
- Stable device identity and reconnect handling.
- Klipper MCU path management.

Gate:
- Sustained MCU serial communication on real hardware.
- Correct handling of unplug/replug and permission revocation.
- Controlled test print with working emergency stop and thermal safeguards.

## Phase 5 — Docker, optional services and multi-printer

Deferred until the base guest is stable:
- Docker Engine/containerd/runc inside Alpine.
- Kernel namespaces, cgroups, OverlayFS, networking and storage driver validation.
- Multiple printer process supervision (current instance scripts are data/config scaffolding only).
- KlipperScreen and XServer-XSDL integration.
- Camera, timelapse and optional print-completion workflows beyond the existing Android music/monitoring shell.

Gate:
- Docker `hello-world`, persistent volumes, DNS, network and clean shutdown pass inside guest.
- Multiple printers run isolated Klipper/Moonraker processes with independent ports and data.
- Optional services do not destabilize printing.

## Phase 6 — release candidate and hardening

- Backup/restore and disk-space safeguards.
- Crash recovery and interrupted-upgrade rollback.
- Security review of QMP, local ports, WebView, CORS, USB and imported bundles.
- SBOM and third-party license notices.
- Performance, thermal, battery and long-duration testing.
- Signed release APK and reproducible runtime checksums.

Gate:
- At least two physical ARM64 devices.
- Sustained printer-connected operation and a supervised test print.
- No service accidentally exposed on the public network.
- User data survives app restart, guest restart and app upgrade.

## Explicit non-claims until their gates pass

Do not call these operational until tested:
- QEMU execution from the Android APK on a physical phone.
- Alpine boot and persistent disk operation.
- Klipper/Moonraker/Mainsail end-to-end operation.
- Docker inside the guest.
- USB passthrough or serial bridging.
- Klipper controlling a real printer or completing a print.

## Build references

- Android CI: `.github/workflows/build-miracle-klipper-v2.yml`
- Alpine guest CI: `.github/workflows/build-alpine-guest-arm64.yml`
- Complete QEMU + Alpine runtime CI: `.github/workflows/build-qemu-android-arm64.yml`
- Runtime format: `docs/v2/android-runtime-bundle.md`
