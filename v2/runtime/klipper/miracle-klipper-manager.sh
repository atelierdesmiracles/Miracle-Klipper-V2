#!/bin/sh
# Miracle Klipper guest operations manager - KIAUH-inspired, Alpine-native.
# Does not use systemd. All destructive operations require explicit confirmation.
set -eu

BASE="/opt/miracle-klipper"
DATA="/var/lib/miracle-klipper"
BACKUPS="$DATA/backups"
STATE="$DATA/state"
LOGS="$DATA/logs"
KLIPPER="$BASE/klipper"
MOONRAKER="$BASE/moonraker"
VENV="$BASE/venvs"
WEBROOT="/var/www/mainsail"

mkdir -p "$BACKUPS" "$STATE" "$LOGS"

log() { printf '%s [%s] %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$1" "$2"; }

component_status() {
  name="$1"; pattern="$2"; pidfile="$STATE/$name.pid"
  if [ -f "$pidfile" ]; then
    pid="$(cat "$pidfile" 2>/dev/null || true)"
    case "$pid" in *[!0-9]*|'') pid="" ;; esac
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
      printf '%s: running (pid %s)\n' "$name" "$pid"; return 0
    fi
  fi
  if pgrep -f "$pattern" >/dev/null 2>&1; then
    printf '%s: running (process scan)\n' "$name"; return 0
  fi
  printf '%s: stopped\n' "$name"
  return 1
}

backup_data() {
  timestamp="$(date -u '+%Y%m%d-%H%M%S')"
  target="$BACKUPS/miracle-klipper-$timestamp.tar.gz"
  mkdir -p "$BACKUPS"
  tar -czf "$target" -C "$DATA" config gcodes state
  [ -s "$target" ] || { echo "Backup archive is empty" >&2; return 1; }
  sha256sum "$target" > "$target.sha256"
  log INFO "Backup created: $target"
  printf '%s\n' "$target"
}

restore_data() {
  archive="$1"
  [ -f "$archive" ] || { echo "Backup archive not found" >&2; return 2; }
  case "$archive" in *.tar.gz) ;; *) echo "Only .tar.gz backup archives are accepted" >&2; return 2 ;; esac
  [ -f "$archive.sha256" ] || { echo "Backup checksum file missing" >&2; return 2; }
  (cd "$(dirname "$archive")" && sha256sum -c "$(basename "$archive").sha256")
  echo "This will replace config, gcodes and state. Type RESTORE to continue:"
  IFS= read -r answer
  [ "$answer" = "RESTORE" ] || { echo "Restore cancelled"; return 1; }
  timestamp="$(date -u '+%Y%m%d-%H%M%S')"
  current="$BACKUPS/pre-restore-$timestamp.tar.gz"
  tar -czf "$current" -C "$DATA" config gcodes state
  tar -xzf "$archive" -C "$DATA"
  log INFO "Restored data from $archive (pre-restore backup: $current)"
}

check_runtime() {
  errors=0
  for item in "$KLIPPER/klippy/klippy.py" "$MOONRAKER/moonraker/moonraker.py"     "$VENV/klipper/bin/python" "$VENV/moonraker/bin/python" "$WEBROOT/index.html"; do
    if [ -e "$item" ]; then printf '[OK] %s\n' "$item"
    else printf '[MISSING] %s\n' "$item"; errors=$((errors+1)); fi
  done

  if [ -f "$DATA/state/klipper-revision" ]; then
    printf 'Klipper revision: %s\n' "$(cat "$DATA/state/klipper-revision")"
  fi
  if [ -f "$DATA/state/moonraker-revision" ]; then
    printf 'Moonraker revision: %s\n' "$(cat "$DATA/state/moonraker-revision")"
  fi
  if [ -S "$DATA/comms/klippy.sock" ]; then echo "[OK] Klipper API socket"
  else echo "[WAIT] Klipper API socket not present"; fi

  if command -v wget >/dev/null 2>&1; then
    if wget -q -O /dev/null http://127.0.0.1:7125/server/info; then
      echo "[OK] Moonraker HTTP API"
    else echo "[WAIT] Moonraker HTTP API unavailable"; fi
    if wget -q -O /dev/null http://127.0.0.1:8080/; then
      echo "[OK] Mainsail HTTP"
    else echo "[WAIT] Mainsail HTTP unavailable"; fi
  fi
  return "$errors"
}

start_services() {
  /usr/local/bin/miracle-klipper-start
}

stop_services() {
  /usr/local/bin/miracle-klipper-stop
}

case "${1:-status}" in
  status)
    component_status klipper '[k]lippy/klippy.py' || true
    component_status moonraker '[m]oonraker/moonraker.py' || true
    component_status nginx '[n]ginx: master process' || true
    check_runtime || true
    ;;
  start) start_services ;;
  stop) stop_services ;;
  restart) stop_services; sleep 1; start_services ;;
  check) check_runtime ;;
  backup) backup_data ;;
  restore)
    [ "$#" -eq 2 ] || { echo "Usage: $0 restore /path/to/backup.tar.gz" >&2; exit 2; }
    restore_data "$2"
    ;;
  logs)
    service="${2:-all}"
    case "$service" in
      klipper) tail -n 100 "$LOGS/klippy.log" "$LOGS/klipper-process.log" ;;
      moonraker) tail -n 100 "$LOGS/moonraker.log" "$LOGS/moonraker-process.log" ;;
      nginx) tail -n 100 /var/log/nginx/* 2>/dev/null || true ;;
      all) tail -n 100 "$LOGS"/*.log ;;
      *) echo "Unknown service: $service" >&2; exit 2 ;;
    esac
    ;;
  *)
    echo "Usage: $0 {status|start|stop|restart|check|backup|restore ARCHIVE|logs [klipper|moonraker|nginx|all]}" >&2
    exit 2
    ;;
esac
