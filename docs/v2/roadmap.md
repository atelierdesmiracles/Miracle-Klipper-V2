# Miracle Klipper V2 — roadmap and acceptance criteria

The project advances only when the current phase passes its gate. A green Kotlin build is not evidence that a Linux VM, Docker, USB, or a printer works.

## Phase 0 — repository and product foundation
Deliverables:
- Separate rewrite/miracle-klipper-v2 branch.
- Architecture and decision records.
- Inventory of reusable Android components from the legacy application.
- Explicit V2 package/namespace and build-toolchain decision.
- CI checks for static analysis, unit tests, and reproducible artifact metadata.

Gate:
- Main branch unchanged.
- V2 scope and non-goals documented.
- No legacy Debian/PRoot installer wired into V2 startup.

## Phase 1 — Android shell and capability preflight
Deliverables:
- Minimal Android app shell for ARM64 only.
- Device report: ABI, Android API level, RAM/storage, USB host support, thermal/battery state.
- Foreground VM service contract and lifecycle state machine.
- User-facing diagnostics and structured logs.

Gate:
- APK installs and launches on physical ARM64 Android devices.
- Non-root operation confirmed.
- Unsupported devices are rejected with a precise reason.
- Start/stop/restart transitions are unit-tested.

## Phase 2 — QEMU and Alpine boot proof
Deliverables:
- Pinned QEMU ARM64 Android build and license notices.
- Pinned Alpine ARM64 guest kernel, initramfs and root filesystem.
- QMP over app-private Unix socket.
- Persistent guest disk and console/log capture.

Gate:
- Clean install boots Alpine to a deterministic readiness signal on a physical phone.
- Guest restarts without losing persistent test data.
- Shutdown does not corrupt the guest filesystem.
- Boot duration, RAM use, CPU load, and thermal behavior recorded.

## Phase 3 — Docker Engine inside guest
Deliverables:
- Verified guest kernel config and Docker prerequisites report.
- Pinned Docker Engine/containerd/runc packages.
- Docker daemon lifecycle integrated with guest init.
- Storage driver and cgroup mode selected from tests, not assumptions.

Gate:
- docker version, docker info, and docker run --rm hello-world succeed inside guest.
- Container networking, DNS, persistent volumes, restart, and clean shutdown pass.
- Docker API is not exposed outside the guest/private control channel.

## Phase 4 — Klipper stack
Deliverables:
- Version-pinned Klipper, Moonraker and Mainsail images/builds.
- Persistent configuration and G-code volumes.
- Health checks, logs, controlled upgrades and rollback.
- Local-only Moonraker/Mainsail forwarding.

Gate:
- All services become healthy after a clean boot.
- Moonraker API and WebSocket work.
- Mainsail loads in Android WebView and reads live Moonraker state.
- No printer hardware required for this phase.

## Phase 5 — USB serial and printer integration
Deliverables:
- Android USB permission/attach/detach manager.
- QEMU USB hot-plug implementation or separately reviewed serial-bridge fallback.
- Stable device identity and reconnect logic.
- Klipper serial path management.

Gate:
- Physical tests with CDC ACM, CH340, CP210x and FTDI devices.
- Disconnect/reconnect and permission revocation handled safely.
- Klipper opens the correct serial device after reconnect.
- Sustained serial communication remains stable under CPU load.

## Phase 6 — operational hardening and release candidate
Deliverables:
- Backup/restore, disk-space controls, update/rollback, crash recovery.
- Security review of ports, CORS, WebView, QMP, Docker and USB.
- Performance/thermal/battery report.
- Compatibility matrix and recovery guide.

Gate:
- Long-duration printer-connected test passes on at least two physical ARM64 devices.
- No uncontrolled remote service exposure.
- Data survives app restart and device reboot.
- Release APK and SBOM/third-party notices published with checksums.

## Required physical test matrix
| Dimension | Minimum coverage |
|---|---|
| Android | At least two ARM64 phones from different vendors; record exact API/kernel |
| Memory | One constrained device and one higher-memory device |
| Virtualization | QEMU TCG baseline; AVF only if separately implemented and capability-detected |
| USB serial | CDC ACM, CH340, CP210x, FTDI |
| Network | Wi-Fi, mobile data, offline/reconnect |
| Lifecycle | Foreground, background, screen off, process recreation, reboot |
| Storage | Fresh disk, near-full disk, backup/restore, interrupted update |
| Runtime | VM boot, Docker hello-world, service health, sustained serial |

## Explicit non-claims until tested
Do not describe these as operational until their gates pass:
- QEMU running from Android APK;
- Alpine guest booting on a physical phone;
- Docker Engine running inside guest;
- USB passthrough;
- Klipper controlling a real printer;
- Mainsail controlling a real printer through Moonraker.

## Phase 2 implementation status — 30 October 2026

Implemented in the V2 branch:
- QEMU command builder and process adapter.
- QMP local-socket client.
- Persistent sparse RAW guest disk provisioning.
- Preflight validation for QEMU artifacts, memory/vCPU limits, QMP socket path length, and device-file paths.
- Pinned QEMU 11.1.2 source archive with SHA-256 verification in CI.
- Android ARM64 debug build and JVM tests passed on commit `22190995b3ae9cb7b9a5545de587cb79a6e05fb6`.

Still blocked on the Phase 2 acceptance gate:
- No Android/Bionic QEMU executable has been built or packaged yet.
- No Alpine ARM64 kernel/initramfs/rootfs has been integrated.
- No physical-phone boot test has been performed.
- The current CI success proves Kotlin unit tests and APK packaging only; it does not prove a bootable VM.

The QEMU source verifier checks the upstream release archive against the pinned digest. It is a source-integrity check, not a QEMU compilation or runtime test.


## Klipper-first delivery order — 30 October 2026

The service rollout is split into independent acceptance gates. Klipper is
implemented and validated as a standalone guest service before Moonraker is
introduced; Mainsail follows after the API layer.

### Phase 4A — Klipper standalone (current work)

- Install only the official, pinned Klipper source inside the Alpine ARM64 guest.
- Create an isolated Python virtual environment and install the requirements
  from that exact source revision.
- Verify the fetched commit, run `pip check`, compile the Klipper Python
  modules, and validate the Klipper command entry point.
- Keep `printer_data` persistent and separate from application binaries.
- Provide explicit start, stop and status commands.
- Never invent printer hardware settings. Klipper startup is blocked until
  `printer.cfg` contains user-provided `[mcu]` and `[printer]` sections.
- Do not install, configure, launch or depend on Moonraker or Mainsail in this
  phase.

Acceptance gate:
- Alpine guest booted on the target runtime.
- Installer succeeds idempotently and records the exact commit.
- Klipper Python environment passes dependency and source checks.
- With a validated simulator/dummy configuration, Klipper starts, creates its
  API socket, reports readiness, and stops cleanly.
- Real printer control remains blocked until USB serial integration is tested.

### Phase 4B — Moonraker API

Only after Phase 4A passes: install a pinned Moonraker revision, configure its
Unix socket connection to Klipper, and validate HTTP/WebSocket state updates.
No Moonraker installer is part of the current Klipper-only deliverable.

### Phase 4C — Mainsail interface

Only after Phase 4B passes: install pinned Mainsail assets and connect the UI
through a loopback-only reverse proxy.
