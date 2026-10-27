#!/usr/bin/env bash
# Double-clickable macOS launcher: runs the Mandi Bidding Engine as a local
# web application on http://localhost:8080 (port configurable via MANDI_PORT).
PORT="${MANDI_PORT:-8080}"
cd "$(dirname "$0")" || exit 1

if ! command -v java >/dev/null 2>&1; then
    echo "Java 17+ is required but was not found on PATH."
    echo "Install a JDK (e.g. Temurin) and try again."
    read -r -p "Press Enter to close..." _
    exit 1
fi

if [ ! -f dist/MandiBiddingEngine.jar ]; then
    echo "Executable JAR not found - packaging it first ..."
    bash dist.sh || { read -r -p "Build failed. Press Enter to close..." _; exit 1; }
fi

( sleep 2 && open "http://localhost:$PORT" ) 2>/dev/null &
java -jar dist/MandiBiddingEngine.jar server "$PORT"
status=$?

echo
read -r -p "Server stopped (exit $status). Press Enter to close..." _
