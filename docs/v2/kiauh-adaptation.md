# KIAUH adaptation notes for Miracle Klipper V2

## Reference and provenance

- Upstream project: https://github.com/dw-0/kiauh
- Upstream revision reviewed: `d3e538de7b3163abe27fdf8116a60716d4db31ce`
- Upstream license: GNU GPL v3.0 (see upstream LICENSE).
- KIAUH is designed for a pre-existing Linux SBC installation and depends on
  `sudo`, systemd service management, distro package managers and Linux device
  groups. Those assumptions are not copied into the Android/Alpine guest.

## KIAUH patterns adopted

| KIAUH capability | Miracle Klipper adaptation |
|---|---|
| Component-oriented installation | Separate, version-locked Klipper, Moonraker and Mainsail component metadata |
| Dedicated Python environments | Separate Klipper and Moonraker venvs |
| Dependency installation | Alpine `apk` packages plus each project's pinned requirements |
| Instance/data directory model | Persistent `printer_data` style tree under `/var/lib/miracle-klipper` |
| Start/stop/restart via instance manager | PID/process supervisor scripts; Android foreground service will own guest lifecycle |
| Status checks | Process, PID, Unix socket and HTTP readiness checks |
| Backup before update | Timestamped archive of persistent config and service settings |
| Config generation | Hardware-neutral template; never invent MCU, kinematics or heater values |
| Nginx web client integration | Loopback-only Mainsail server with Moonraker REST/WebSocket reverse proxy |
| Firmware build/flash | Separate future feature, gated on USB serial/DFU detection and successful toolchain validation |
| Optional extensions | Registry/dependency checks; optional modules installed independently, not bundled blindly |

## Capabilities intentionally not copied as-is

- systemd unit files and `systemctl` calls: unavailable in our current Alpine guest plan.
- sudo, usermod, dialout/tty group changes: inappropriate for a single-user guest supervised by Android.
- Debian/Ubuntu apt package lists: translated only after checking Alpine package names and ABI.
- Interactive terminal dialogs: replaced by Android app controls and structured command results.
- Direct USB flash routines: unsafe to enable before Android USB Host permission, device identity,
  serial bridge/passthrough and board-specific bootloader behavior are proven.

## GPL-3.0 compliance boundary

This integration is an independent implementation inspired by KIAUH's public architecture.
No KIAUH Python module or copied implementation is included in Miracle Klipper at this stage.
If source code from KIAUH is copied or modified later, retain copyright headers, include GPL-3.0
license text, mark modified files, and satisfy source and redistribution obligations for the
combined distributed work. The source repository remains the authoritative license reference.

## Reviewed KIAUH feature families

- Core: Klipper, Moonraker, Mainsail setup/update/remove services.
- Operations: instance manager, service state, backup service and update safeguards.
- Web: nginx upstreams, WebSocket reverse proxy and static client install.
- Hardware: firmware configuration/build/flash workflows.
- Optional: camera service, touchscreen UI, spool management, notifications and community
  extensions. These require independent compatibility and security review before inclusion.

The reference review does not imply these optional components are installed or operational.
