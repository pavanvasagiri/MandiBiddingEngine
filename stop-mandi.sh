#!/usr/bin/env bash
# stop-mandi.sh - Stop the Mandi Bidding Engine HTTP server.
set -euo pipefail
cd "$(dirname "$0")"
PIDFILE="server.pid"

if [ ! -f "$PIDFILE" ]; then
  echo "No server.pid found - nothing to stop."
  exit 0
fi

pid="$(cat "$PIDFILE" 2>/dev/null || true)"
if [ -z "$pid" ]; then
  rm -f "$PIDFILE"
  echo "No PID in server.pid."
  exit 0
fi

if kill -0 "$pid" 2>/dev/null; then
  kill "$pid" 2>/dev/null || true
  waited=0
  while kill -0 "$pid" 2>/dev/null && [ "$waited" -lt 15 ]; do
    sleep 1
    waited=$((waited+1))
  done
  if kill -0 "$pid" 2>/dev/null; then
    kill -9 "$pid" 2>/dev/null || true
    echo "Force-killed Mandi server (PID $pid)."
  else
    echo "Stopped Mandi server (PID $pid)."
  fi
else
  echo "Mandi server (PID $pid) was not running."
fi
rm -f "$PIDFILE"
echo "Done."