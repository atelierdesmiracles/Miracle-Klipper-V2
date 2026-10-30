# Multi-printer and firmware tools

These scripts are installed by `install-klipper-alpine.sh`.

## Multiple printer profiles

Commands:

```sh
miracle-klipper-instances list
miracle-klipper-instances create ender3
miracle-klipper-instances create voron
miracle-klipper-instances remove ender3
```

Each profile gets its own `config`, `gcodes`, `logs` and `comms` directories,
a printer configuration template, a Moonraker configuration and an allocated
local port starting at 7125. Removing a profile requires typing an exact
confirmation phrase and creates a pre-removal archive.

**Current limitation:** this creates and manages isolated data/configuration
profiles only. The existing runtime launcher still manages the primary printer.
It does not yet register, start, stop or route additional Moonraker/Klipper
processes. The Android process supervisor, per-instance API endpoints and
Mainsail instance selection must be implemented and tested before calling
multi-printer runtime support complete.

## Klipper firmware build

Commands:

```sh
miracle-klipper-firmware list
miracle-klipper-firmware menuconfig my-board
miracle-klipper-firmware build my-board
miracle-klipper-firmware clean my-board
```

Each named profile uses an isolated Kconfig file under
`/var/lib/miracle-klipper/firmware-configs`. Build artifacts and SHA-256
checksums are copied to `/var/lib/miracle-klipper/firmware/PROFILE`.

This is build-only. It does not flash a controller. Before flashing can be
implemented, the app must validate Android USB Host permissions, USB device
identity, stable serial passthrough into Alpine, supported bootloader protocol,
board-specific flash method and safe recovery behavior. Never flash a board
using an unverified profile.

## Important safety

The generated `printer.cfg` is deliberately hardware-neutral. It contains no
invented MCU path, kinematics, thermistor, heater, stepper or motion values.
A printer profile must be configured for the actual machine before Klipper is
started.
