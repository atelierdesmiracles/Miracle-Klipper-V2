#!/bin/sh
# Install Klipper only in the Alpine ARM64 guest. Moonraker/Mainsail are excluded.
set -eu
KLIPPER_REPO="https://github.com/Klipper3d/klipper.git"
KLIPPER_REV="\${KLIPPER_REV:-ee2c6fb6b00b2b5d097ef70d0868df7d74943b51}"
BASE="/opt/miracle-klipper"
DATA="/var/lib/miracle-klipper"
SOURCE="$BASE/klipper"
STAGING="$BASE/klipper-staging"
VENV="$BASE/venvs/klipper"
LOG="$DATA/logs/install-klipper.log"

case "$KLIPPER_REV" in *[!0-9a-fA-F]*|'') echo "Invalid Klipper commit SHA" >&2; exit 2 ;; esac
[ "\${#KLIPPER_REV}" -eq 40 ] || { echo "Klipper revision must be a full 40-character SHA" >&2; exit 2; }

mkdir -p "$BASE" "$BASE/venvs" "$DATA/config" "$DATA/logs" "$DATA/gcodes" "$DATA/comms" "$DATA/state"
exec >>"$LOG" 2>&1

echo "[Miracle Klipper] Installing Alpine host dependencies"
apk add --no-cache ca-certificates git python3 py3-pip build-base linux-headers musl-dev libffi-dev openssl-dev

if [ -f "$DATA/state/klipper-revision" ] &&
   [ "$(cat "$DATA/state/klipper-revision")" = "$KLIPPER_REV" ] &&
   [ -f "$SOURCE/klippy/klippy.py" ] && [ -x "$VENV/bin/python" ]; then
  echo "[Miracle Klipper] Requested revision already installed"
else
  rm -rf "$STAGING"
  mkdir -p "$STAGING"
  git -C "$STAGING" init -q
  git -C "$STAGING" remote add origin "$KLIPPER_REPO"
  git -C "$STAGING" fetch --depth=1 origin "$KLIPPER_REV"
  git -C "$STAGING" checkout --detach FETCH_HEAD
  ACTUAL="$(git -C "$STAGING" rev-parse HEAD)"
  [ "$ACTUAL" = "$KLIPPER_REV" ] || { echo "Klipper revision mismatch" >&2; exit 1; }

  rm -rf "$VENV"
  python3 -m venv "$VENV"
  "$VENV/bin/python" -m pip install --disable-pip-version-check --no-input -r "$STAGING/scripts/klippy-requirements.txt"
  "$VENV/bin/python" -m pip check
  "$VENV/bin/python" -m compileall -q "$STAGING/klippy"
  "$VENV/bin/python" "$STAGING/klippy/klippy.py" --help >/dev/null

  rm -rf "$SOURCE"
  mv "$STAGING" "$SOURCE"
  printf '%s\n' "$KLIPPER_REV" > "$DATA/state/klipper-revision.tmp"
  mv "$DATA/state/klipper-revision.tmp" "$DATA/state/klipper-revision"
fi

if [ ! -f "$DATA/config/printer.cfg" ]; then
  cat > "$DATA/config/printer.cfg" <<'EOF'
# Miracle Klipper - neutral printer configuration template.
# Configure the correct MCU serial path, kinematics, steppers, heaters,
# thermistors, motion limits and safety settings for YOUR printer.
# Do not start Klipper until valid [mcu] and [printer] sections exist.
EOF
fi

cat > /usr/local/bin/miracle-klipper-start <<'EOF'
#!/bin/sh
set -eu
DATA="/var/lib/miracle-klipper"
BASE="/opt/miracle-klipper"
CFG="$DATA/config/printer.cfg"
SOCKET="$DATA/comms/klippy.sock"
[ -x "$BASE/venvs/klipper/bin/python" ] || { echo "Klipper venv missing" >&2; exit 1; }
[ -f "$BASE/klipper/klippy/klippy.py" ] || { echo "Klipper source missing" >&2; exit 1; }
[ -f "$CFG" ] || { echo "printer.cfg missing" >&2; exit 1; }
grep -Eq '^[[:space:]]*\[mcu\][[:space:]]*$' "$CFG" || { echo "printer.cfg needs [mcu]" >&2; exit 1; }
grep -Eq '^[[:space:]]*\[printer\][[:space:]]*$' "$CFG" || { echo "printer.cfg needs [printer]" >&2; exit 1; }
if pgrep -f '[k]lippy/klippy.py' >/dev/null 2>&1; then echo "Klipper already running"; exit 0; fi
mkdir -p "$DATA/logs" "$DATA/comms" "$DATA/state"
rm -f "$SOCKET"
nohup "$BASE/venvs/klipper/bin/python" "$BASE/klipper/klippy/klippy.py" \
  "$CFG" -l "$DATA/logs/klippy.log" -a "$SOCKET" \
  >>"$DATA/logs/klipper-process.log" 2>&1 &
echo "$!" > "$DATA/state/klipper.pid"
echo "Klipper launch requested; check logs and socket for readiness."
EOF
chmod +x /usr/local/bin/miracle-klipper-start

cat > /usr/local/bin/miracle-klipper-stop <<'EOF'
#!/bin/sh
set -u
DATA="/var/lib/miracle-klipper"
if [ -f "$DATA/state/klipper.pid" ]; then
  PID="$(cat "$DATA/state/klipper.pid")"
  case "$PID" in *[!0-9]*|'') ;; *) kill "$PID" 2>/dev/null || true ;; esac
  rm -f "$DATA/state/klipper.pid"
fi
echo "Klipper stop requested"
EOF
chmod +x /usr/local/bin/miracle-klipper-stop

cat > /usr/local/bin/miracle-klipper-status <<'EOF'
#!/bin/sh
if pgrep -f '[k]lippy/klippy.py' >/dev/null 2>&1; then echo "Klipper: process running"; else echo "Klipper: stopped"; fi
if [ -S /var/lib/miracle-klipper/comms/klippy.sock ]; then echo "Klipper API socket: present"; else echo "Klipper API socket: not ready"; fi
EOF
chmod +x /usr/local/bin/miracle-klipper-status

echo "[Miracle Klipper] Klipper-only installation completed at revision $KLIPPER_REV"
echo "Moonraker and Mainsail have not been installed."
