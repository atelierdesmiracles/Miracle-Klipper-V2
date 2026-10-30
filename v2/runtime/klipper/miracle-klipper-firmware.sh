#!/bin/sh
# Klipper firmware builder for Alpine guest. Build-only: flashing is deliberately excluded.
set -eu

KLIPPER="/opt/miracle-klipper/klipper"
OUT="/var/lib/miracle-klipper/firmware"
CONFIG_DIR="/var/lib/miracle-klipper/firmware-configs"

usage() {
  echo "Usage:"
  echo "  $0 menuconfig PROFILE"
  echo "  $0 build PROFILE"
  echo "  $0 clean PROFILE"
  echo "  $0 list"
  echo
  echo "PROFILE must be an existing .config file under $CONFIG_DIR."
  echo "No MCU is flashed by this tool."
}

valid_profile() {
  name="$1"
  printf '%s\n' "$name" | grep -Eq '^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$'
}

profile_config() {
  valid_profile "$1" || { echo "Invalid profile name" >&2; exit 2; }
  printf '%s/%s.config\n' "$CONFIG_DIR" "$1"
}

check_toolchain() {
  [ -d "$KLIPPER" ] || { echo "Klipper source missing: $KLIPPER" >&2; exit 1; }
  [ -f "$KLIPPER/Makefile" ] || { echo "Klipper Makefile missing" >&2; exit 1; }
  command -v make >/dev/null 2>&1 || { echo "make is not installed" >&2; exit 1; }
  command -v gcc >/dev/null 2>&1 || { echo "gcc is not installed" >&2; exit 1; }
  command -v python3 >/dev/null 2>&1 || { echo "python3 is not installed" >&2; exit 1; }
}

list_profiles() {
  mkdir -p "$CONFIG_DIR"
  found=0
  for file in "$CONFIG_DIR"/*.config; do
    [ -f "$file" ] || continue
    found=1
    printf '%s\n' "${file##*/}"
  done
  [ "$found" -eq 1 ] || echo "No firmware profiles yet. Run menuconfig PROFILE."
}

cmd="${1:-}"
profile="${2:-}"
case "$cmd" in
  list) list_profiles ;;
  menuconfig)
    [ "$#" -eq 2 ] || { usage; exit 2; }
    check_toolchain
    config="$(profile_config "$profile")"
    mkdir -p "$CONFIG_DIR"
    if [ ! -f "$config" ]; then
      cp "$KLIPPER/.config" "$config" 2>/dev/null || : > "$config"
    fi
    make -C "$KLIPPER" PYTHON=python3 KCONFIG_CONFIG="$config" menuconfig
    ;;
  build)
    [ "$#" -eq 2 ] || { usage; exit 2; }
    check_toolchain
    config="$(profile_config "$profile")"
    [ -s "$config" ] || { echo "Profile missing/empty. Run menuconfig first." >&2; exit 1; }
    mkdir -p "$OUT/$profile"
    make -C "$KLIPPER" PYTHON=python3 KCONFIG_CONFIG="$config" clean
    make -C "$KLIPPER" PYTHON=python3 KCONFIG_CONFIG="$config"
    count=0
    for ext in bin elf hex uf2; do
      if [ -f "$KLIPPER/out/klipper.$ext" ]; then
        cp "$KLIPPER/out/klipper.$ext" "$OUT/$profile/"
        sha256sum "$OUT/$profile/klipper.$ext" >> "$OUT/$profile/SHA256SUMS"
        count=$((count + 1))
      fi
    done
    [ "$count" -gt 0 ] || { echo "Build completed but no known firmware artifact was found" >&2; exit 1; }
    printf 'Build artifacts for %s saved in %s\n' "$profile" "$OUT/$profile"
    echo "Build only: inspect board/bootloader/communication settings before any flash."
    ;;
  clean)
    [ "$#" -eq 2 ] || { usage; exit 2; }
    check_toolchain
    config="$(profile_config "$profile")"
    [ -f "$config" ] || { echo "Profile not found" >&2; exit 1; }
    make -C "$KLIPPER" PYTHON=python3 KCONFIG_CONFIG="$config" clean
    ;;
  *) usage; exit 2 ;;
esac
