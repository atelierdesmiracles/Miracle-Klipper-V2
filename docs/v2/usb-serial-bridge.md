# USB serial bridge — Miracle Klipper V2

## Data path

```text
Printer MCU / USB adapter
  → Android USB Host API + usb-serial-for-android
  → loopback TCP listener 127.0.0.1:8023 (Android app process)
  → QEMU socket chardev
  → virtio-serial device / org.miracle.usbserial
  → Alpine /dev/virtio-ports/org.miracle.usbserial
  → socat PTY /dev/ttyUSB0
  → Klipper [mcu] serial
```

This is a byte-stream serial bridge. It is not raw USB passthrough and does not expose the USB device or bridge port to the LAN. QEMU connects to an Android loopback listener; Alpine sees a virtio serial port.

## Android setup

1. Connect the printer controller using a USB OTG adapter/hub.
2. Open Miracle Klipper and select **Scanner les périphériques USB**.
3. Select the adapter and set its baud rate. The default is 250000; use the value required by the MCU firmware.
4. Select **Connecter la carte sélectionnée** and grant Android USB permission.
5. Start the VM. The bridge server is initialized before the VM launch and QEMU reconnects to it.
6. The app handles USB detach by closing the serial port and resetting the QEMU stream so a new connection can be accepted.

The Android library is pinned to `com.github.mik3y:usb-serial-for-android:3.11.0`. Its upstream driver support includes generic CDC/ACM, FTDI, CP210x and CH340 families. Device compatibility still depends on the actual USB adapter, firmware, Android host controller and OTG power.

## Alpine and Klipper setup

The guest starts `socat` between the virtio port and `/dev/ttyUSB0`. Configure the actual MCU path in:

`/var/lib/miracle-klipper/config/printer.cfg`

Example only — use the baud rate and MCU identifier appropriate to the printer:

```ini
[mcu]
serial: /dev/ttyUSB0
baud: 250000
```

Do not copy printer kinematics, thermistor, heater or stepper settings from another machine. Those are hardware-specific safety parameters.

## Validation still required

- Android CI must resolve and compile the pinned JitPack dependency.
- Test CDC/ACM, CH340, CP210x and FTDI adapters on physical Android phones.
- Test duplicate VID/PID devices and confirm selection uses the USB device path, not VID/PID alone.
- Test USB permission denial, detach, reattach and reconnect while QEMU remains active.
- Verify MCU communication at the configured baud rate under sustained CPU load.
- Confirm Klipper reconnect behavior and complete a supervised test print with emergency stop and thermal safeguards.

Until these tests pass, the bridge is an implemented integration path, not a certified printer connection.
