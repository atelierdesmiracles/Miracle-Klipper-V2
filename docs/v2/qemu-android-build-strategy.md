# Android ARM64 QEMU build strategy

## Decision

Use the upstream QEMU release source and the Termux Android/Bionic packaging recipe as the first reproducible build reference. Do not use a generic Linux/Ubuntu QEMU binary: it targets glibc and is not directly loadable by Android's Bionic runtime.

Pinned source:
- QEMU: 11.1.2
- Archive: https://download.qemu.org/qemu-11.1.2.tar.xz
- SHA-256: `731b5681e4bb18be313231579b8efd0296c5b015fa36dc533874b639ba838016`
- Reference Termux recipe commit: `0e1306a2df4492ce5113fe220b65c4c4a4bf6e2d`
- Reference recipe: `x11-packages/qemu-system-x86-64/build.sh`
- Target: `aarch64-softmmu`
- Android ABI: `arm64-v8a`
- Required accelerator baseline: TCG, not KVM.

The exact source pin is also recorded in `v2/runtime/qemu/qemu-source.lock`.

## Why the Termux recipe matters

The Termux recipe builds QEMU against Android/Termux's Bionic environment and explicitly includes the `aarch64-softmmu` system target. It demonstrates that QEMU's Android host port is a real build target, rather than assuming a desktop Linux binary can be copied into an APK.

The reference recipe currently enables many optional subsystems and lists a broad dependency set. Miracle Klipper should not blindly bundle all of them. The production build must be headless and should initially retain only:
- AArch64 system emulation;
- TCG multi-threading;
- ARM `virt` machine;
- serial console;
- raw VirtIO block;
- VirtIO networking with user-mode networking;
- QMP over a private Unix socket.

Disable GUI, audio, SDL, GTK, VNC, SPICE, USB redirection, and unrelated guest architectures unless a measured requirement appears.

## Packaging constraints

An Android APK must contain the QEMU executable and every non-platform shared library it needs. At packaging time:
1. Inspect dependencies with the NDK/LLVM `llvm-readelf` (do not rely on host `ldd`).
2. Reject unresolved ELF dependencies.
3. Keep Android system libraries dynamically linked to the platform; bundle only permitted non-platform libraries.
4. Set executable permissions on the native executable after extraction to app-private storage.
5. Preserve upstream and dependency license texts and notices in the app.
6. Record the source archive SHA-256, build recipe commit, NDK version, compiler flags, and output binary SHA-256.
7. Run a smoke test on a physical Android ARM64 device before enabling VM startup in the UI.

Do not copy Termux runtime libraries into an APK without reviewing their licenses, Android linker compatibility, SONAMEs, and transitive dependencies.

## Important limitations

- Source availability and a checksum do not mean the Android binary has been built.
- The Termux recipe is a build reference, not a prebuilt binary we can safely redistribute.
- TCG is CPU-intensive; performance and thermal behavior must be measured on actual phones.
- The QEMU process controller must remain disconnected from the production Start button until the binary and guest images are installed and verified.
- The current V2 app does not yet ship QEMU, Alpine images, or a bootable guest.

## Next implementation gates

1. Create a minimal headless Termux-compatible QEMU package recipe for `aarch64-softmmu`.
2. Build the recipe in a pinned Termux build environment and collect the complete package/dependency closure.
3. Produce an APK-native bundle with ELF dependency and license audits.
4. Run `qemu-system-aarch64 --version` and a no-guest smoke test on a physical ARM64 phone.
5. Only then integrate the Alpine ARM64 kernel, initramfs, and root filesystem.
