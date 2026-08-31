#!/usr/bin/env bash
#
# Every check that does not need a running Minecraft server.
#
#   ./tools/run-tests.sh
#
# Java is usually not on PATH on this machine; JAVA_HOME is set below if it
# is not already. The compiled classpath is regenerated when missing, because
# a hand-rolled one made from `find` broke the suites once.
set -euo pipefail
cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@25}"
export PATH="$JAVA_HOME/bin:$PATH"

T=tools/tests
OUT=build/toolstests
mkdir -p "$OUT"

./gradlew build -q
[ -s "$T/cp.txt" ] || ./gradlew -I "$T/cp.gradle" printCp -q > "$T/cp.txt"
CP="$(cat "$T/cp.txt"):build/classes/java/main:build/resources/main"

# The panel is a Java string; dump it, then drive its script under a DOM stub.
javac -cp "$CP" -d "$OUT" "$T/PageDump.java"
java -cp "$CP:$OUT" PageDump "$OUT/panel.html" "$OUT/panel.js"
node "$T/panelsmoke.js" "$OUT/panel.js"

# The BlueMap bridge is another Java text block, served from BlueMap's webroot.
# Check its emitted JavaScript too, independently of the panel bundle.
javac -cp "$CP" -d "$OUT" "$T/BlueMapBridgeDump.java"
java -cp "$CP:$OUT" BlueMapBridgeDump "$OUT/bluemap-bridge.js"
node --check "$OUT/bluemap-bridge.js"

bad=""
for f in "$T"/*Tests.java "$T"/AssetPick.java "$T"/PayloadTypes.java; do
  t=$(basename "$f" .java)
  javac -cp "$CP" -d "$OUT" "$f" >/dev/null 2>&1 || { bad="$bad $t(compile)"; continue; }
  java -Dfixtures=tools/fixtures -Dharness=tools/harness -cp "$CP:$OUT" "$t" >/dev/null 2>&1 \
    || bad="$bad $t"
done
echo "JAVA FAILED:${bad:- none}"
[ -z "$bad" ]
