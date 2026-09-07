#!/usr/bin/env bash
# Builds and runs the Mandi Bidding Engine simulation.
# Requires: JDK 17+ (JAVA_HOME on PATH) and the running MySQL server described
# in config/db.properties.
set -euo pipefail
cd "$(dirname "$0")"

echo "[1/3] Compiling with javac (--release 17) ..."
rm -rf out
mkdir -p out
find src test -name '*.java' > build.sources
javac --release 17 -Xlint:all,-serial -cp lib/mysql-connector-j-8.3.0.jar -d out @build.sources
rm -f build.sources

echo "[2/3] Running engine regression tests ..."
java -cp "out:lib/mysql-connector-j-8.3.0.jar" com.mandi.engine.DoubleAuctionEngineTest

echo "[3/3] Running the live mandi simulation (main) ..."
java -cp "out:lib/mysql-connector-j-8.3.0.jar" com.mandi.main.Main

echo "Done. See historical_mandi_bids.csv for the settlement log."
