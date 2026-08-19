#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "Usage: build-target.sh <minecraft-26.x-version> [output-directory]" >&2
  exit 2
fi

MC_VERSION="$1"
OUTPUT_DIR="${2:-}"
GRADLE_VERSION="${MINESPORT_GRADLE_VERSION:-9.5.1}"
LOOM_VERSION="${MINESPORT_LOOM_VERSION:-1.17.18}"

if [[ ! "$MC_VERSION" =~ ^26\. ]]; then
  echo "The dynamic bridge builder targets Minecraft 26.x. Use the legacy bridge for 1.21.x worlds." >&2
  exit 2
fi

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_PROJECT_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"

need() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing required command: $1" >&2
    exit 1
  }
}

need curl
need python3
need unzip
need java

JAVA_MAJOR="$(java -version 2>&1 | head -n1 | python3 -c 'import re,sys; m=re.search(r"\"(\d+)",sys.stdin.read()); print(m.group(1) if m else "0")')"
if (( JAVA_MAJOR < 25 )); then
  echo "Java 25+ is required for Minecraft 26.x Fabric builds (found Java $JAVA_MAJOR)." >&2
  exit 1
fi

resolve_versions() {
  python3 - "$MC_VERSION" <<'PY'
import json, re, sys, urllib.request, urllib.parse
from html import unescape

game = sys.argv[1]
headers = {"User-Agent": "Minesport-BridgeBuilder/0.2"}

req = urllib.request.Request(
    "https://meta.fabricmc.net/v2/versions/loader/" + urllib.parse.quote(game, safe=""),
    headers=headers,
)
with urllib.request.urlopen(req) as r:
    loaders = json.load(r)
if not loaders:
    raise SystemExit(f"No Fabric Loader build exists for {game}")
stable = next((x for x in loaders if x.get("loader", {}).get("stable")), loaders[0])
loader = stable["loader"]["version"]

req = urllib.request.Request(
    "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/",
    headers=headers,
)
with urllib.request.urlopen(req) as r:
    text = r.read().decode("utf-8", "replace")
versions = set(unescape(x) for x in re.findall(r'href="([^"/]+(?:\+|%2B)[^"/]+)/"', text))
compat = [game]
m = re.match(r"^(\d+\.\d+)", game)
if m and m.group(1) not in compat:
    compat.append(m.group(1))

def key(value):
    prefix = value.split("+", 1)[0]
    nums = re.findall(r"\d+", prefix)
    return tuple(int(x) for x in nums)

api = None
for target in compat:
    matches = [v for v in versions if v.endswith("+" + target)]
    if matches:
        api = sorted(matches, key=key)[-1]
        break
if not api:
    raise SystemExit(f"No Fabric API artifact exists for {game}")

print(loader)
print(api)
PY
}

mapfile -t RESOLVED < <(resolve_versions)
LOADER_VERSION="${RESOLVED[0]}"
FABRIC_API_VERSION="${RESOLVED[1]}"

if command -v gradle >/dev/null 2>&1; then
  GRADLE_BIN="$(command -v gradle)"
else
  CACHE_BASE="${XDG_CACHE_HOME:-$HOME/.cache}/minesport"
  GRADLE_HOME="$CACHE_BASE/gradle-$GRADLE_VERSION"
  GRADLE_BIN="$GRADLE_HOME/bin/gradle"
  if [[ ! -x "$GRADLE_BIN" ]]; then
    mkdir -p "$CACHE_BASE"
    ZIP="$CACHE_BASE/gradle-$GRADLE_VERSION-bin.zip"
    echo "[Minesport Bridge] Downloading Gradle $GRADLE_VERSION..."
    curl -fL "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$ZIP"
    unzip -q -o "$ZIP" -d "$CACHE_BASE"
    rm -f "$ZIP"
  fi
fi

SAFE_VERSION="${MC_VERSION//[^A-Za-z0-9._-]/_}"
WORK_ROOT="${XDG_CACHE_HOME:-$HOME/.cache}/minesport/bridge-build"
PROJECT_DIR="$WORK_ROOT/$SAFE_VERSION"
rm -rf "$PROJECT_DIR"
mkdir -p "$PROJECT_DIR"

shopt -s dotglob nullglob
for entry in "$SOURCE_PROJECT_DIR"/*; do
  base="$(basename "$entry")"
  if [[ "$base" == "build" || "$base" == ".gradle" ]]; then
    continue
  fi
  cp -R "$entry" "$PROJECT_DIR/"
done
shopt -u dotglob nullglob

echo "[Minesport Bridge] Minecraft:  $MC_VERSION"
echo "[Minesport Bridge] Loader:     $LOADER_VERSION"
echo "[Minesport Bridge] Fabric API: $FABRIC_API_VERSION"
echo "[Minesport Bridge] Loom:       $LOOM_VERSION"
echo "[Minesport Bridge] Workspace:  $PROJECT_DIR"

"$GRADLE_BIN" \
  -p "$PROJECT_DIR" \
  clean build \
  "-Pminecraft_version=$MC_VERSION" \
  "-Ploader_version=$LOADER_VERSION" \
  "-Pfabric_api_version=$FABRIC_API_VERSION" \
  "-Ploom_version=$LOOM_VERSION" \
  --no-daemon \
  --stacktrace

JAR="$(find "$PROJECT_DIR/build/libs" -maxdepth 1 -type f -name '*.jar' ! -name '*-sources.jar' -print | sort | head -n1)"
if [[ -z "$JAR" ]]; then
  echo "Gradle completed but no bridge JAR was produced." >&2
  exit 1
fi

if [[ -z "$OUTPUT_DIR" ]]; then
  OUTPUT_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/Minesport/bridges/$MC_VERSION"
fi
mkdir -p "$OUTPUT_DIR"
cp -f "$JAR" "$OUTPUT_DIR/"
RESULT="$OUTPUT_DIR/$(basename "$JAR")"
echo "[Minesport Bridge] Built: $RESULT"
printf '%s\n' "$RESULT"
