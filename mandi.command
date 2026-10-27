#!/usr/bin/env bash
# Double-clickable macOS launcher for the Mandi Bidding Engine application.
# Builds the executable JAR on first use, then runs the simulation and keeps
# the Terminal window open so the financial report can be read.
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

java -jar dist/MandiBiddingEngine.jar
status=$?

echo
read -r -p "Application finished (exit $status). Press Enter to close..." _
