# Architecture documentation

The V2 architecture is maintained in [docs/v2/architecture.md](v2/architecture.md). This file is retained as a compatibility pointer; older PRoot/Debian and Docker-first diagrams are not authoritative for Miracle Klipper V2.

Current design: native Android ARM64 host → QEMU TCG → Alpine ARM64 guest → Klipper + Moonraker + Mainsail. USB serial is bridged through a private loopback socket and a guest virtio-serial PTY. Docker remains optional and gated on kernel validation.
