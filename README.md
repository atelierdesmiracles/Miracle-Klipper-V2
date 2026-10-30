# Miracle Klipper V2

Miracle Klipper V2 is a native Android ARM64 application designed to host an isolated Alpine Linux virtual machine and run Klipper, Moonraker and Mainsail without requiring Android root access.

> **Status:** active development. Android runtime import, QEMU process lifecycle, Alpine guest image build pipeline, loopback port forwarding and embedded Mainsail WebView are being integrated. A CI build or ELF audit is not proof of a successful guest boot or a working printer connection. Do not use this branch for unattended printing until physical-device and printer tests pass.

## Target architecture

```text
Android native app (arm64-v8a, API 26+)
  ├─ Device diagnostics and runtime importer
  ├─ Foreground VM lifecycle service
  ├─ QEMU AArch64 / TCG
  │    ├─ Alpine Linux ARM64 (linux-virt)
  │    ├─ Klipper
  │    ├─ Moonraker
  │    └─ nginx + Mainsail
  └─ Local-only forwarded endpoints
       ├─ 127.0.0.1:8080 → Mainsail
       └─ 127.0.0.1:7125 → Moonraker
```

The guest is isolated from the Android host. The application forwards the Mainsail and Moonraker ports to Android loopback only. The persistent writable guest disk is copied from the initial runtime bundle once; runtime upgrades must not overwrite printer configuration or G-code data.

## Android application

- Application ID: `fr.atelierdesmiracles.miracleklipper.v2`
- Minimum Android API: 26 (Android 8.0)
- Native ABI target: `arm64-v8a`
- Root: not required by the design.
- Linux execution: QEMU TCG; no PRoot or Debian bootstrap.
- Runtime import: ZIP archive with a `manifest.json` and SHA-256 hashes for every payload file.
- QEMU: launched and owned by an Android foreground service.
- Mainsail: embedded WebView served from `http://127.0.0.1:8080`.
- Moonraker: local endpoint `http://127.0.0.1:7125`.

The SHA-256 manifest detects payload corruption or mismatch; it does not, by itself, authenticate the publisher of an imported ZIP. Only install runtime bundles produced by the project build pipeline.

## Build and test Android V2

From the repository root, with JDK 17, Android SDK platform 35 and Gradle 8.9:

```bash
gradle -p v2/android testDebugUnitTest
gradle -p v2/android assembleDebug
gradle -p v2/android assembleRelease
```

The GitHub Actions workflow `.github/workflows/build-miracle-klipper-v2.yml` runs unit tests, builds both variants, checks their application IDs and uploads APK artifacts. Release APKs produced by CI are unsigned unless a controlled signing configuration is added.

## Build the Alpine ARM64 guest

The guest builder uses the pinned Alpine 3.24.3 minirootfs, the `linux-virt` kernel package, mkinitfs, an ext4 root filesystem and a QCOW2 persistent disk. It uses QEMU user-mode emulation on the build runner to install ARM64 Alpine packages.

```bash
sudo apt-get install qemu-user-static binfmt-support e2fsprogs qemu-utils
sudo update-binfmts --enable qemu-aarch64
sudo bash scripts/build-alpine-guest.sh ./alpine-guest-output
```

The standalone guest build workflow is `.github/workflows/build-alpine-guest-arm64.yml`.

## Build the complete QEMU + Alpine runtime bundle

The QEMU workflow builds a headless AArch64 system emulator from the pinned Termux package recipe, stages its shared-library closure and license notices, audits the Android ELF interpreter and dependencies, builds the Alpine guest, and assembles a ZIP archive with a SHA-256 manifest.

```bash
bash scripts/prepare-qemu-headless-recipe.sh termux-packages/x11-packages/qemu-system-x86-64/build.sh
bash scripts/assemble-runtime-bundle.sh ./qemu-output ./alpine-guest-output ./runtime-output
```

The complete production build is orchestrated by `.github/workflows/build-qemu-android-arm64.yml`. Do not bundle a QEMU package if the ELF audit or recursive dependency-closure check fails.

## Alpine guest services

On first boot, OpenRC starts the Miracle Klipper bootstrap. The guest installs pinned Klipper and Moonraker revisions, installs the pinned Mainsail release, configures nginx and enables the local service manager.

The initial `printer.cfg` is deliberately hardware-neutral. Before starting Klipper, configure the actual MCU serial path, kinematics, stepper limits, thermistors, heaters and safety limits for the specific printer. The project must never generate invented heater or motion settings.

KIAUH is used as an architectural reference for component installation, Python virtual environments, service operations, backups and instance management. Miracle Klipper uses Alpine/OpenRC-native scripts; it does not copy KIAUH's Debian/systemd assumptions.

## Current limitations

- A successful Android compile does not prove QEMU can execute on a physical phone.
- Alpine first boot and Moonraker/Klipper readiness still require end-to-end validation on ARM64 hardware.
- USB serial passthrough/bridge to a printer MCU is not yet validated.
- Docker inside the guest is deferred until kernel namespaces, cgroups, overlayfs and storage behavior are tested.
- Multiple printer instances currently have isolated data/config scaffolding; multi-process supervision is not yet complete.
- Firmware build helpers are present, but flashing is deliberately disabled until USB identity and bootloader workflows are validated.
- KlipperScreen and XServer-XSDL are not yet part of the validated V2 runtime.

## Safety and acceptance

A printer can move hot components and energize heaters. Keep an accessible physical power cutoff, never leave early test prints unattended, and validate thermal protection and emergency-stop behavior before printing.

A V2 release is not considered operational until all of the following are demonstrated on a physical ARM64 Android device:

1. Install the APK and import the runtime bundle.
2. Start Alpine in QEMU and confirm the guest boot log.
3. Confirm Moonraker HTTP and WebSocket access through loopback.
4. Open Mainsail in the embedded WebView.
5. Connect to a real printer MCU and verify stable serial communication.
6. Complete a controlled test print with working emergency stop and thermal safeguards.

See [V2 runtime bundle contract](docs/v2/android-runtime-bundle.md), [KIAUH adaptation notes](docs/v2/kiauh-adaptation.md) and [project roadmap](docs/v2/roadmap.md).
