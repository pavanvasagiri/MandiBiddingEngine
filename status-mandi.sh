#!/usr/bin/env bash
# status-mandi.sh - Show Mandi Bidding Engine server + MySQL health.
set -uo pipefail
cd "$(dirname "$0")"
PORT="${MANDI_PORT:-8080}"

echo "=== Mandi Bidding Engine status ==="
echo "HTTP port: $PORT"
echo "Dashboard: http://localhost:$PORT"

if [ -f server.pid ]; then
  pid="$(cat server.pid 2>/dev/null || true)"
  echo "PID file: ${pid:-none}"
  if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
    echo "Process:  RUNNING (PID $pid)"
  else
    echo "Process:  NOT RUNNING (stale server.pid)"
  fi
else
  echo "PID file: none"
fi

if curl -s --max-time 3 "http://localhost:$PORT/api/health" >/dev/null 2>&1; then
  echo "Health:   $(curl -s --max-time 3 "http://localhost:$PORT/api/health")"
  echo "Status:   UP"
else
  echo "Health:   DOWN (nothing on http://localhost:$PORT/api/health)"
  echo "Status:   DOWN"
fi

echo
echo "=== MySQL (127.0.0.1:3306) ==="
if /usr/local/mysql/bin/mysql -h 127.0.0.1 -P 3306 -u root -padmin123 -e "SELECT 1" >/dev/null 2>&1; then
  echo "MySQL: UP"
else
  echo "MySQL: DOWN / not reachable (start with: sudo /usr/local/mysql/support-files/mysql.server start)"
fi