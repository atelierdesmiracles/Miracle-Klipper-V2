#!/bin/sh
# Miracle Klipper V2 - complete printer stack for Alpine Linux ARM64.
# Installs Klipper, Moonraker, Mainsail and nginx directly in the guest.
# Docker is intentionally not a dependency until the guest kernel passes its tests.
set -eu

KLIPPER_REV="ee2c6fb6b00b2b5d097ef70d0868df7d74943b51"
MOONRAKER_REV="b559b9a81161f958a43c2f527b6ad96f73310d5d"
MAINSAIL_VERSION="v2.19.0"
MAINSAIL_SHA256="c4d9d96f89851c6ae0709c2f20725447f3fe8c57cf193869b0083441bd93378a"

BASE="/opt/miracle-klipper"
DATA="/var/lib/miracle-klipper"
VENV="$BASE/venvs"
CONFIG="$DATA/config"
LOGS="$DATA/logs"
COMMS="$DATA/comms"
WEBROOT="/var/www/mainsail"

mkdir -p "$BASE" "$VENV" "$CONFIG" "$LOGS" "$DATA/gcodes" "$COMMS" "$DATA/state"
exec >>"$LOGS/install-stack.log" 2>&1

echo "[Miracle Klipper] Installing Alpine dependencies"
apk add --no-cache ca-certificates curl git nginx openrc socat acpid python3 py3-pip py3-virtualenv unzip procps \
  build-base linux-headers musl-dev libffi-dev openssl-dev jpeg-dev zlib-dev

# Handle QEMU's ACPI power-button event for a clean guest shutdown.
mkdir -p /etc/acpi/events
cat > /etc/acpi/events/miracle-power-button <<'EOF'
event=button/power.*
action=/sbin/poweroff
EOF
rc-update add acpid default
rc-service acpid start || true

fetch_pinned() {
  repo="$1"
  destination="$2"
  revision="$3"
  staging="$destination.staging"
  rm -rf "$staging"
  mkdir -p "$staging"
  git -C "$staging" init -q
  git -C "$staging" remote add origin "$repo"
  git -C "$staging" fetch --depth=1 origin "$revision"
  git -C "$staging" checkout --detach FETCH_HEAD
  actual="$(git -C "$staging" rev-parse HEAD)"
  if [ "$actual" != "$revision" ]; then
    echo "Pinned revision mismatch for $repo: expected $revision, got $actual" >&2
    exit 1
  fi
  rm -rf "$destination"
  mv "$staging" "$destination"
}

install_python_component() {
  component="$1"
  source_dir="$2"
  requirements="$3"
  environment="$VENV/$component"
  python3 -m venv "$environment"
  "$environment/bin/python" -m pip install --disable-pip-version-check --no-input \
    -r "$source_dir/$requirements"
  "$environment/bin/python" -m pip check
}

echo "[Miracle Klipper] Fetching pinned Klipper source"
if [ ! -f "$BASE/klipper/klippy/klippy.py" ] || \
   [ ! -f "$DATA/state/klipper-revision" ] || \
   [ "$(cat "$DATA/state/klipper-revision")" != "$KLIPPER_REV" ]; then
  fetch_pinned "https://github.com/Klipper3d/klipper.git" "$BASE/klipper" "$KLIPPER_REV"
  rm -rf "$VENV/klipper"
  install_python_component "klipper" "$BASE/klipper" "scripts/klippy-requirements.txt"
  "$VENV/klipper/bin/python" -m compileall -q "$BASE/klipper/klippy"
  "$VENV/klipper/bin/python" "$BASE/klipper/klippy/klippy.py" --help >/dev/null
  printf '%s\n' "$KLIPPER_REV" > "$DATA/state/klipper-revision.tmp"
  mv "$DATA/state/klipper-revision.tmp" "$DATA/state/klipper-revision"
fi

echo "[Miracle Klipper] Fetching pinned Moonraker source"
if [ ! -f "$BASE/moonraker/moonraker/moonraker.py" ] || \
   [ ! -f "$DATA/state/moonraker-revision" ] || \
   [ "$(cat "$DATA/state/moonraker-revision")" != "$MOONRAKER_REV" ]; then
  fetch_pinned "https://github.com/Arksine/moonraker.git" "$BASE/moonraker" "$MOONRAKER_REV"
  rm -rf "$VENV/moonraker"
  install_python_component "moonraker" "$BASE/moonraker" "scripts/moonraker-requirements.txt"
  "$VENV/moonraker/bin/python" -m compileall -q "$BASE/moonraker/moonraker"
  "$VENV/moonraker/bin/python" "$BASE/moonraker/moonraker/moonraker.py" --help >/dev/null
  printf '%s\n' "$MOONRAKER_REV" > "$DATA/state/moonraker-revision.tmp"
  mv "$DATA/state/moonraker-revision.tmp" "$DATA/state/moonraker-revision"
fi

echo "[Miracle Klipper] Downloading and verifying Mainsail"
mkdir -p "$WEBROOT"
tmp_zip="/tmp/mainsail-release.zip"
curl --fail --location --retry 3 --connect-timeout 20 \
  "https://github.com/mainsail-crew/mainsail/releases/download/$MAINSAIL_VERSION/mainsail.zip" \
  -o "$tmp_zip"
echo "$MAINSAIL_SHA256  $tmp_zip" | sha256sum -c -
rm -rf /tmp/mainsail-unpack
mkdir -p /tmp/mainsail-unpack
unzip -q "$tmp_zip" -d /tmp/mainsail-unpack
if [ -f /tmp/mainsail-unpack/index.html ]; then
  rm -rf "$WEBROOT"/*
  cp -a /tmp/mainsail-unpack/. "$WEBROOT/"
elif [ -f /tmp/mainsail-unpack/mainsail/index.html ]; then
  rm -rf "$WEBROOT"/*
  cp -a /tmp/mainsail-unpack/mainsail/. "$WEBROOT/"
else
  echo "Mainsail archive layout is not recognized" >&2
  exit 1
fi
rm -rf "$tmp_zip" /tmp/mainsail-unpack

if [ ! -f "$CONFIG/printer.cfg" ]; then
  cat > "$CONFIG/printer.cfg" <<'EOF'
# Miracle Klipper - neutral printer configuration.
# Example USB bridge (uncomment after connecting the printer):
# [mcu]
# serial: /dev/ttyMiracleMCU
# Add the printer-specific [printer], stepper, heater, thermistor and safety sections.
# stepper, heater, thermistor and safety sections before starting Klipper.
EOF
fi

if [ ! -f "$CONFIG/moonraker.conf" ]; then
  cat > "$CONFIG/moonraker.conf" <<EOF
[server]
host: 0.0.0.0
port: 7125
klippy_uds_address: $COMMS/klippy.sock
max_upload_size: 1024

[authorization]
trusted_clients:
  127.0.0.0/8
  ::1/128
  10.0.2.0/24
cors_domains:
  localhost
  127.0.0.1
  http://127.0.0.1
  http://127.0.0.1:8080
  http://localhost
  http://localhost:8080

[file_manager]
enable_object_processing: True
config_path: $CONFIG

[history]

[announcements]
subscriptions:
  mainsail

[update_manager]
enable_auto_refresh: False
EOF
fi

mkdir -p /etc/nginx/http.d
cat > /etc/nginx/http.d/miracle-klipper.conf <<EOF
server {
    listen 0.0.0.0:8080;
    server_name localhost;
    root $WEBROOT;
    index index.html;
    client_max_body_size 1024m;

    location / {
        try_files \$uri \$uri/ /index.html;
    }

    location /websocket {
        proxy_pass http://127.0.0.1:7125/websocket;
        proxy_http_version 1.1;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host \$http_host;
        proxy_read_timeout 3600s;
    }

    location ~ ^/(printer|api|access|machine|server|debug|announcements|webhooks|register_remote_method) {
        proxy_pass http://127.0.0.1:7125\$request_uri;
        proxy_http_version 1.1;
        proxy_set_header Host \$http_host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
    }
}
EOF
nginx -t

cat > /usr/local/bin/miracle-klipper-start <<'EOF'
#!/bin/sh
set -eu
BASE="/opt/miracle-klipper"
DATA="/var/lib/miracle-klipper"
CONFIG="$DATA/config"
LOGS="$DATA/logs"
COMMS="$DATA/comms"
mkdir -p "$LOGS" "$COMMS" "$DATA/state"

if pgrep -f '[n]ginx: master process' >/dev/null 2>&1; then
  echo "Nginx already running"
else
  nginx
fi

if pgrep -f '[m]oonraker/moonraker.py' >/dev/null 2>&1; then
  echo "Moonraker already running"
else
  nohup /opt/miracle-klipper/venvs/moonraker/bin/python     /opt/miracle-klipper/moonraker/moonraker/moonraker.py     -c "$CONFIG/moonraker.conf"     >>"$LOGS/moonraker-process.log" 2>&1 &
  echo "$!" > "$DATA/state/moonraker.pid"
fi

if [ -x /etc/init.d/miracle-klipper-usb-serial ]; then
  rc-service miracle-klipper-usb-serial start || true
fi

if grep -Eq '^[[:space:]]*\[mcu\][[:space:]]*$' "$CONFIG/printer.cfg" &&
   grep -Eq '^[[:space:]]*\[printer\][[:space:]]*$' "$CONFIG/printer.cfg"; then
  if pgrep -f '[k]lippy/klippy.py' >/dev/null 2>&1; then
    echo "Klipper already running"
  else
    rm -f "$COMMS/klippy.sock"
    nohup /opt/miracle-klipper/venvs/klipper/bin/python       /opt/miracle-klipper/klipper/klippy/klippy.py       "$CONFIG/printer.cfg" -l "$LOGS/klippy.log" -a "$COMMS/klippy.sock"       >>"$LOGS/klipper-process.log" 2>&1 &
    echo "$!" > "$DATA/state/klipper.pid"
  fi
else
  echo "Klipper is installed but not started: configure [mcu] and [printer] in $CONFIG/printer.cfg"
fi

echo "Mainsail: http://127.0.0.1:8080/"
echo "Moonraker: http://127.0.0.1:7125/"
EOF
chmod +x /usr/local/bin/miracle-klipper-start

cat > /usr/local/bin/miracle-klipper-stop <<'EOF'
#!/bin/sh
set -u
DATA="/var/lib/miracle-klipper"
for service in klipper moonraker; do
  pidfile="$DATA/state/$service.pid"
  if [ -f "$pidfile" ]; then
    pid="$(cat "$pidfile")"
    case "$pid" in *[!0-9]*|'') ;; *) kill "$pid" 2>/dev/null || true ;; esac
    rm -f "$pidfile"
  fi
done
rc-service miracle-klipper-usb-serial stop 2>/dev/null || true
nginx -s quit 2>/dev/null || true
echo "Miracle Klipper services stop requested"
EOF
chmod +x /usr/local/bin/miracle-klipper-stop

cat > /usr/local/bin/miracle-klipper-status <<'EOF'
#!/bin/sh
set +e
pgrep -f '[k]lippy/klippy.py' >/dev/null 2>&1 && echo "Klipper: running" || echo "Klipper: stopped"
pgrep -f '[m]oonraker/moonraker.py' >/dev/null 2>&1 && echo "Moonraker: running" || echo "Moonraker: stopped"
pgrep -f '[n]ginx: master process' >/dev/null 2>&1 && echo "Mainsail/Nginx: running" || echo "Mainsail/Nginx: stopped"
rc-service miracle-klipper-usb-serial status >/dev/null 2>&1 && echo "USB serial bridge: running" || echo "USB serial bridge: stopped"
[ -L /dev/ttyMiracleMCU ] && echo "Klipper MCU PTY: ready" || echo "Klipper MCU PTY: unavailable"
[ -S /var/lib/miracle-klipper/comms/klippy.sock ] && echo "Klipper API socket: ready" || echo "Klipper API socket: not ready"
EOF
chmod +x /usr/local/bin/miracle-klipper-status

# Expose Android USB serial over a PTY that pyserial/Klipper can open.
cat > /usr/local/sbin/miracle-klipper-usb-serial <<'EOF'
#!/bin/sh
set -eu
DEVICE="/dev/virtio-ports/org.miracle.usbserial"
attempt=0
while [ ! -c "$DEVICE" ] && [ "$attempt" -lt 30 ]; do
  attempt=$((attempt + 1))
  sleep 1
done
if [ ! -c "$DEVICE" ]; then
  echo "Virtio serial endpoint is not available: $DEVICE" >&2
  exit 1
fi
rm -f /dev/ttyMiracleMCU
exec socat -d -d "PTY,link=/dev/ttyMiracleMCU,raw,echo=0,mode=660" "OPEN:$DEVICE"
EOF
chmod 0755 /usr/local/sbin/miracle-klipper-usb-serial

cat > /etc/init.d/miracle-klipper-usb-serial <<'EOF'
#!/sbin/openrc-run
description="Android USB serial to Klipper PTY bridge"
command="/usr/local/sbin/miracle-klipper-usb-serial"
command_background=true
pidfile="/run/miracle-klipper-usb-serial.pid"
output_log="/var/log/miracle-klipper-usb-serial.log"
error_log="/var/log/miracle-klipper-usb-serial.log"
depend() {
  need local
  before miracle-klipper
}
EOF
chmod 0755 /etc/init.d/miracle-klipper-usb-serial
rc-update add miracle-klipper-usb-serial default
rc-service miracle-klipper-usb-serial start || true

echo "[Miracle Klipper] Stack installation complete."
echo "Pinned Klipper: $KLIPPER_REV"
echo "Pinned Moonraker: $MOONRAKER_REV"
echo "Pinned Mainsail: $MAINSAIL_VERSION"
echo "Start with: miracle-klipper-start"

# KIAUH-inspired operational manager
install -m 0755 "$(dirname "$0")/miracle-klipper-manager.sh" /usr/local/bin/miracle-klipper-manager
install -m 0755 "$(dirname "$0")/miracle-klipper-instances.sh" /usr/local/bin/miracle-klipper-instances
install -m 0755 "$(dirname "$0")/miracle-klipper-firmware.sh" /usr/local/bin/miracle-klipper-firmware

cat > /etc/init.d/miracle-klipper <<'EOF'
#!/sbin/openrc-run
description="Miracle Klipper printer stack"
depend() {
  need networking
}
start() {
  ebegin "Starting Miracle Klipper services"
  /usr/local/bin/miracle-klipper-start
  eend $?
}
stop() {
  ebegin "Stopping Miracle Klipper services"
  /usr/local/bin/miracle-klipper-stop
  eend $?
}
EOF
chmod 0755 /etc/init.d/miracle-klipper
if command -v rc-update >/dev/null 2>&1; then
  rc-update add miracle-klipper default || true
fi
