#!/usr/bin/env bash
set -euo pipefail

RECIPE="${1:?usage: prepare-qemu-headless-recipe.sh PATH/TO/qemu-system-x86-64/build.sh}"
python3 - "$RECIPE" <<'PY'
from pathlib import Path
import re
import sys

path = Path(sys.argv[1])
text = path.read_text()

text, count = re.subn(
    r'^TERMUX_PKG_DEPENDS=.*$',
    'TERMUX_PKG_DEPENDS="dtc, glib, libpixman, libslirp, qemu-common, resolv-conf, zlib, zstd"',
    text,
    count=1,
    flags=re.M,
)
if count != 1:
    raise SystemExit("Could not replace QEMU runtime dependency declaration")

text = re.sub(r'^TERMUX_PKG_BUILD_DEPENDS=.*\n', '', text, flags=re.M)
text = re.sub(r'^TERMUX_PKG_ANTI_BUILD_DEPENDS=.*\n', '', text, flags=re.M)

configure = r'''termux_step_configure() {
	termux_setup_ninja

	local QEMU_TARGETS="aarch64-softmmu"

	CFLAGS+=" $CPPFLAGS"
	CXXFLAGS+=" $CPPFLAGS"
	LDFLAGS+=" -landroid-shmem -llog"

	./configure \
		--prefix="$TERMUX_PREFIX" \
		--cross-prefix="${TERMUX_HOST_PLATFORM}-" \
		--host-cc="gcc" \
		--cc="$CC" \
		--cxx="$CXX" \
		--objcc="$CC" \
		--disable-stack-protector \
		--enable-coroutine-pool \
		--disable-guest-agent \
		--disable-gtk \
		--disable-sdl \
		--disable-sdl-image \
		--disable-opengl \
		--disable-virglrenderer \
		--disable-vnc \
		--disable-spice \
		--disable-curl \
		--disable-libssh \
		--disable-libusb \
		--disable-usb-redir \
		--disable-virtfs \
		--disable-libnfs \
		--disable-vhost-user \
		--disable-vhost-user-blk-server \
		--disable-xen \
		--disable-xen-pci-passthrough \
		--disable-hvf \
		--disable-whpx \
		--disable-kvm \
		--disable-seccomp \
		--disable-snappy \
		--disable-lzfse \
		--disable-vte \
		--disable-bochs \
		--disable-cloop \
		--disable-dmg \
		--disable-parallels \
		--disable-qed \
		--disable-bzip2 \
		--disable-lzo \
		--disable-gnutls \
		--disable-nettle \
		--disable-iconv \
		--disable-curses \
		--disable-png \
		--disable-vnc-jpeg \
		--enable-fdt=system \
		--enable-slirp \
		--audio-drv-list= \
		--target-list="$QEMU_TARGETS"
}

termux_step_post_make_install() {
	ln -sfr \
		"$TERMUX_PREFIX"/share/man/man1/qemu.1 \
		"$TERMUX_PREFIX"/share/man/man1/qemu-system-aarch64.1
}
'''
text, count = re.subn(
    r'termux_step_configure\(\) \{.*?\n\}',
    configure.rstrip(),
    text,
    count=1,
    flags=re.S,
)
if count != 1:
    raise SystemExit("Could not replace QEMU configure function")
path.write_text(text)
print(f"Prepared headless QEMU recipe: {path}")
PY

grep -E '^TERMUX_PKG_DEPENDS=|--target-list=|--disable-gtk|--enable-slirp' "$RECIPE"
