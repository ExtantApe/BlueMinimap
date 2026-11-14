#!/usr/bin/env bash
# Builds one throwaway Paper server on 127.0.0.1, runs live tests against it, and deletes it, even when a
# test fails. See README.md in this folder.
#
# Usage: test/live/run.sh <paper version> [test...]
#   tests: suite (default), suite:<group,group>, packurl, storage, load, backoff
#   e.g.   test/live/run.sh 26.3 suite packurl storage load backoff
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
WORK=$(mkdir -p "${BMH_LIVE_DIR:-$HERE/work}" && cd "${BMH_LIVE_DIR:-$HERE/work}" && pwd)
PORT=${BMH_PORT:-25693}
PACK_PORT=${BMH_PACK_PORT:-8193}
WEB_PORT=${BMH_WEB_PORT:-8123}
HOST_PORT=${BMH_HOST_PORT:-8293}

VERSION=${1:?Usage: run.sh <paper version> [suite|suite:<groups>|packurl|storage|load|backoff ...]}
shift
TESTS=("${@:-suite}")

if [ "${BMH_ACCEPT_EULA:-}" != "1" ]; then
  echo "This starts a Minecraft server and lets BlueMap download the Minecraft client's resources."
  echo "Both mean accepting the Minecraft EULA (https://aka.ms/MinecraftEULA). Set BMH_ACCEPT_EULA=1 to accept it."
  exit 2
fi

PAPER=paper-$VERSION.jar
HUD_JAR=$(ls "$REPO"/build/libs/BlueMapMini-*.jar 2>/dev/null | head -1)
HELPER_JAR=$REPO/test/live/helper/build/libs/LiveTestHelper.jar
CONFLICT_JAR=$REPO/test/live/conflict/build/libs/LiveTestConflict.jar
missing=0
for f in "$WORK/$PAPER" "$WORK/bluemap.jar" "$WORK/ViaVersion.jar" "$WORK/ViaBackwards.jar"; do
  [ -f "$f" ] || { echo "Missing $f. See README.md for what goes in the work folder."; missing=1; }
done
for f in "$HUD_JAR" "$HELPER_JAR" "$CONFLICT_JAR"; do
  [ -n "$f" ] && [ -f "$f" ] || { echo "Missing a built jar. Run ./gradlew build first."; missing=1; }
done
[ -d "$HERE/node_modules/mineflayer" ] || { echo "Run npm ci in test/live first."; missing=1; }
[ $missing = 0 ] || exit 2

NAME=server-$VERSION
SERVER=$WORK/$NAME
remove_server() {
  # Only ever a server folder inside the work folder.
  case "$SERVER" in "$WORK"/server-*) rm -rf -- "$SERVER" ;; esac
}
trap remove_server EXIT
remove_server

echo "== Building a throwaway Paper $VERSION server in $SERVER"
mkdir -p "$SERVER/plugins/BlueMapMini"
echo "eula=true" > "$SERVER/eula.txt"
cat > "$SERVER/server.properties" <<EOF
server-ip=127.0.0.1
server-port=$PORT
query.port=$PORT
online-mode=false
white-list=false
enforce-whitelist=false
spawn-protection=0
level-seed=4242
view-distance=6
simulation-distance=6
motd=BlueMapMini throwaway test server
EOF
cp "$WORK/bluemap.jar" "$SERVER/plugins/BlueMap.jar"
cp "$WORK/ViaVersion.jar" "$WORK/ViaBackwards.jar" "$SERVER/plugins/"
cp "$HELPER_JAR" "$SERVER/plugins/"
cp "$HUD_JAR" "$SERVER/plugins/BlueMapMini.jar"
# Set the ports before the first start, so the defaults never clash with another server on this machine.
sed -e "s/^  port: 8164$/  port: $PACK_PORT/" -e "s#http://127.0.0.1:8100#http://127.0.0.1:$WEB_PORT#" \
  "$REPO/src/main/resources/config.yml" > "$SERVER/plugins/BlueMapMini/config.yml"

cd "$HERE"
node firstboot.js "$NAME" "$PAPER" "$PORT" > "$WORK/$NAME-boot.log" 2>&1 || { echo "First start failed:"; tail -5 "$WORK/$NAME-boot.log"; exit 1; }
sed -i.bak -e 's/^accept-download: false/accept-download: true/' "$SERVER/plugins/BlueMap/core.conf"
sed -i.bak -e "s/^port: .*/port: $WEB_PORT/" "$SERVER/plugins/BlueMap/webserver.conf"
rm -f "$SERVER"/plugins/BlueMap/*.bak
echo "== Generating terrain and rendering it with BlueMap"
node prep.js "$NAME" "$PAPER" "$PORT" > "$WORK/$NAME-prep.log" 2>&1 || { echo "Prep failed:"; tail -5 "$WORK/$NAME-prep.log"; exit 1; }
grep -E "^(world|errors)" "$WORK/$NAME-prep.log" | tail -3 || true

status=0
for test in "${TESTS[@]}"; do
  log=$WORK/$NAME-${test%%:*}.log
  echo "== $test (log: $log)"
  case "$test" in
    suite) node suite.js "$VERSION" "$NAME" "$PAPER" "$PORT" "$PACK_PORT" "$WEB_PORT" world_the_nether > "$log" 2>&1 || status=1 ;;
    suite:*) node suite.js "$VERSION" "$NAME" "$PAPER" "$PORT" "$PACK_PORT" "$WEB_PORT" world_the_nether "${test#suite:}" > "$log" 2>&1 || status=1 ;;
    load) node suite.js "$VERSION" "$NAME" "$PAPER" "$PORT" "$PACK_PORT" "$WEB_PORT" world_the_nether load > "$log" 2>&1 || status=1 ;;
    packurl) node packurl.js "$VERSION" "$NAME" "$PAPER" "$PORT" "$PACK_PORT" "$WEB_PORT" "$HOST_PORT" > "$log" 2>&1 || status=1 ;;
    storage) node storage.js "$NAME" "$PAPER" "$PORT" "$WEB_PORT" "$PACK_PORT" > "$log" 2>&1 || status=1 ;;
    backoff) node backoff.js "$NAME" "$PAPER" "$PORT" "$CONFLICT_JAR" > "$log" 2>&1 || status=1 ;;
    houserules) # The real HouseRules, current and 0.1.1: BMH_HOUSERULES_JAR and BMH_HOUSERULES_OLD_JAR.
      if [ -f "${BMH_HOUSERULES_JAR:-}" ] && [ -f "${BMH_HOUSERULES_OLD_JAR:-}" ]; then
        node houserules.js "$NAME" "$PAPER" "$PORT" "$BMH_HOUSERULES_JAR" "$BMH_HOUSERULES_OLD_JAR" > "$log" 2>&1 || status=1
      else echo "SKIP  houserules  [set BMH_HOUSERULES_JAR and BMH_HOUSERULES_OLD_JAR]" | tee "$log"; fi ;;
    *) echo "Unknown test: $test"; status=1; continue ;;
  esac
  grep -E "^(FAIL|SKIP)|^SUMMARY|CRASHED" "$log" || true
done
echo "== Deleting $SERVER"
exit $status
