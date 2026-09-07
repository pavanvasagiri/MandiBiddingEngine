#!/usr/bin/env bash
# Packages the Mandi Bidding Engine as a self-contained executable JAR:
#   dist/MandiBiddingEngine.jar
# (application classes + MySQL Connector/J merged, Main-Class = com.mandi.main.Main)
# After packaging, run it from ANY directory:  java -jar dist/MandiBiddingEngine.jar
set -euo pipefail
cd "$(dirname "$0")"

echo "[1/4] Compiling sources (Java 17 target) ..."
rm -rf out build
mkdir -p out build/stage dist
find src test -name '*.java' > build/sources.txt
javac --release 17 -Xlint:all,-serial -cp lib/mysql-connector-j-8.3.0.jar -d out @build/sources.txt

echo "[2/4] Staging application classes ..."
cp -R out/. build/stage/

echo "[3/4] Merging MySQL Connector/J ..."
CONNECTOR_JAR="$(pwd)/lib/mysql-connector-j-8.3.0.jar"
(cd build/stage && jar xf "$CONNECTOR_JAR")
rm -f build/stage/module-info.class \
      build/stage/META-INF/MANIFEST.MF \
      build/stage/META-INF/versions/*/module-info.class \
      build/stage/META-INF/*.SF build/stage/META-INF/*.DSA build/stage/META-INF/*.RSA

echo "[4/4] Creating executable JAR ..."
jar --create --file dist/MandiBiddingEngine.jar --main-class com.mandi.main.Main -C build/stage .
rm -rf build

echo
echo "Packaged dist/MandiBiddingEngine.jar ($(du -h dist/MandiBiddingEngine.jar | cut -f1))"
echo "Run it from anywhere with:  java -jar \"$(pwd)/dist/MandiBiddingEngine.jar\""
