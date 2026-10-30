# QEMU Android ARM64 package build

This workflow is intentionally manual because QEMU is a large native package build and can consume substantial CI time.

It uses the Termux package builder at the exact source commit recorded in the QEMU lock file and invokes the documented Termux package build command for the aarch64 host package. The output is currently a package artifact for inspection, not yet a production-ready APK payload.

## Run

Open GitHub Actions → **Build QEMU Android ARM64 package** → **Run workflow** on branch `rewrite/miracle-klipper-v2`.

## What this first build stage proves

- The pinned Termux package builder can configure and compile its Android/Bionic QEMU package.
- The resulting package archive can be retained as a CI artifact for inspection.
- The QEMU package is built for Termux's AArch64 Android environment, not a generic glibc Linux host.

## What it does not prove

- That the package is minimal or safe to bundle as-is.
- That every shared-library dependency is suitable for an APK.
- That the emulator launches from Android app-private storage.
- That an Alpine guest boots on a physical device.

The next packaging stage must extract the QEMU system binary and its non-platform shared-library closure, inspect ELF DT_NEEDED/RUNPATH entries with LLVM tools, preserve license notices, and generate the exact SHA-256 digest consumed by QemuBinaryVerifier. The binary must then pass a physical-device smoke test before guest boot is enabled.
