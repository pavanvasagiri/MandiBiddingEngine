#!/usr/bin/env bash
# start-mandi.sh - Reliably start the Mandi Bidding Engine dashboard on localhost.
#
# Ensures MySQL is reachable on 127.0.0.1:3306, compiles the Java sources if
# needed, (re)starts the Mandi HTTP server, waits for /api/health to report UP,
# and opens the dashboard in your browser.
#
# Usage: ./start-mandi.sh           # default http://localhost:8080
#        MANDI_PORT=9090 ./start-mandi.sh   # custom port
set -euo pipefail

cd "$(dirname "$0")"
ROOT="$(pwd)"
PORT="${MANDI_PORT:-8080}"
PIDFILE="$ROOT/server.pid"
LOGFILE="$ROOT/server.log"
CLASSPATH="out:lib/mysql-connector-j-8.3.0.jar"
MAX_WAIT=40

log() { printf '[start-mandi] %s\n' "$*"; }

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || { echo "ERROR: $1 not found on PATH" >&2; exit 1; };
}
need_cmd java
need_cmd javac

# ---------- 1. Ensure MySQL is reachable on 127.0.0.1:3306 ----------
mysql_ready() {
  /usr/local/mysql/bin/mysql -h 127.0.0.1 -P 3306 -u root -padmin123 -e "SELECT 1" >/dev/null 2>&1
}

if ! mysql_ready; then
  log "MySQL not reachable on 127.0.0.1:3306 - attempting to start ..."
  if [ -x /usr/local/mysql/support-files/mysql.server ]; then
    sudo /usr/local/mysql/support-files/mysql.server start >/dev/null 2>&1 || \
    /usr/local/mysql/support-files/mysql.server start >/dev/null 2>&1 || true
  elif command -v launchctl >/dev/null 2>&1 && [ -f /Library/LaunchDaemons/com.oracle.oss.mysql.mysqld.plist ]; then
    sudo launchctl load /Library/LaunchDaemons/com.oracle.oss.mysql.mysqld.plist >/dev/null 2>&1 || true
  fi
  waited=0
  while ! mysql_ready; do
    if [ "$waited" -ge "$MAX_WAIT" ]; then
      echo "ERROR: MySQL did not become reachable within ${MAX_WAIT}s." >&2
      echo "       Start MySQL manually (e.g. sudo /usr/local/mysql/support-files/mysql.server start), then re-run." >&2
      exit 1
    fi
    sleep 2
    waited=$((waited+2))
  done
fi
log "MySQL is reachable on 127.0.0.1:3306"

# ---------- 2. Handle an already-running / stale server ----------
if [ -f "$PIDFILE" ]; then
  old_pid="$(cat "$PIDFILE" 2>/dev/null || true)"
  if [ -n "$old_pid" ] && kill -0 "$old_pid" 2>/dev/null; then
    if curl -s --max-time 3 "http://localhost:$PORT/api/health" >/dev/null 2>&1; then
      log "Server already running (PID $old_pid) and healthy on http://localhost:$PORT"
      open "http://localhost:$PORT" 2>/dev/null || true
      exit 0
    fi
    log "Existing server (PID $old_pid) not healthy - stopping it first ..."
    kill "$old_pid" 2>/dev/null || true
    slept=0
    while kill -0 "$old_pid" 2>/dev/null && [ "$slept" -lt 15 ]; do
      sleep 1; slept=$((slept+1))
    done
    kill -9 "$old_pid" 2>/dev/null || true
  fi
  log "Removing stale server.pid"
  rm -f "$PIDFILE"
fi

# ---------- 3. Compile if needed ----------
need_compile=0
if [ ! -d out ] || [ ! -f "out/com/mandi/server/MandiServerMain.class" ]; then
  need_compile=1
else
  newest_src="$(find src test -name '*.java' -newer out/com/mandi/server/MandiServerMain.class 2>/dev/null | head -1)"
  if [ -n "$newest_src" ]; then need_compile=1; fi
fi

if [ "$need_compile" -eq 1 ]; then
  log "Compiling Java sources (--release 17) ..."
  rm -rf out
  mkdir -p out
  find src test -name '*.java' > build.sources
  javac --release 17 -Xlint:all,-serial -cp lib/mysql-connector-j-8.3.0.jar -d out @build.sources
  rm -f build.sources
  log "Compilation complete."
fi

# ---------- 4. Start the server ----------
log "Starting Mandi HTTP server on http://localhost:$PORT ..."
nohup java -cp "$CLASSPATH" com.mandi.server.MandiServerMain "$PORT" > "$LOGFILE" 2>&1 &
echo $! > "$PIDFILE"
server_pid="$(cat "$PIDFILE")"

# ---------- 5. Wait for health ----------
log "Waiting for server to become healthy ..."
waited=0
while ! curl -s --max-time 3 "http://localhost:$PORT/api/health" >/dev/null 2>&1; do
  if ! kill -0 "$server_pid" 2>/dev/null; then
    echo "ERROR: Server process (PID $server_pid) exited. See $LOGFILE:" >&2
    tail -30 "$LOGFILE" >&2 || true
    exit 1
  fi
  if [ "$waited" -ge "$MAX_WAIT" ]; then
    echo "ERROR: Server did not become healthy within ${MAX_WAIT}s. See $LOGFILE:" >&2
    tail -30 "$LOGFILE" >&2 || true
    exit 1
  fi
  sleep 2
  waited=$((waited+2))
done
log "Server is healthy on http://localhost:$PORT (PID $server_pid)"

# ---------- 6. Open browser ----------
open "http://localhost:$PORT" 2>/dev/null || true
log "Dashboard: http://localhost:$PORT   |   API health: http://localhost:$PORT/api/health   |   Stop: ./stop-mandi.sh"