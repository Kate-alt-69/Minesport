#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERSION="0.2.0"
BRIDGE_VERSION="0.2.0"
BUILD_DEB=false
BUILD_APPIMAGE=false
DESKTOP_ONLY=false
FRESH=false
CHECK_ONLY=false
STATE_FILE="$ROOT/dist/.minesport-build-state"
BUNDLED_DIR="$ROOT/dist/bundled-bridge"

show_help() {
  cat <<'EOF'
Minesport smart build script

Usage:
  ./build.sh [options]

Default:
  Runs a fast Rust/Slint check when reusable artifacts exist, then rebuilds
  only source trees whose SHA-256 fingerprint changed. Unchanged loader Bridge
  projects and the Java engine skip Gradle entirely.

Fast development:
  --check                    Rust + Slint error check only; never runs Gradle
  --desktop-only             Reuse existing engine + all four loader Bridges
  --fresh                    Remove build outputs/state and rebuild everything
                             while preserving Gradle/Cargo/JDK caches

Installer options:
  --build-installer           Build default Linux package (.deb)
  --build-installer-all       Build .deb + .AppImage
  --build-installer-deb       Build .deb
  --build-installer-appimage  Build AppImage

Help:
  -h, --help                  Show help
EOF
}

for arg in "$@"; do
  case "$arg" in
    -h|--help) show_help; exit 0 ;;
    --check) CHECK_ONLY=true ;;
    --fresh) FRESH=true ;;
    --desktop-only) DESKTOP_ONLY=true ;;
    --build-installer) BUILD_DEB=true ;;
    --build-installer-all) BUILD_DEB=true; BUILD_APPIMAGE=true ;;
    --build-installer-deb) BUILD_DEB=true ;;
    --build-installer-appimage) BUILD_APPIMAGE=true ;;
    --build-installer-exe|--build-installer-msi)
      echo "ERROR: $arg is Windows-only; use build.ps1/build.bat." >&2
      exit 2
      ;;
    *)
      echo "ERROR: unknown option: $arg" >&2
      echo "Run ./build.sh --help." >&2
      exit 2
      ;;
  esac
done

if $CHECK_ONLY && $FRESH; then
  echo "ERROR: --check and --fresh cannot be combined." >&2
  exit 2
fi
if $CHECK_ONLY && $DESKTOP_ONLY; then
  echo "ERROR: --check and --desktop-only cannot be combined." >&2
  exit 2
fi
if $FRESH && $DESKTOP_ONLY; then
  echo "ERROR: --fresh and --desktop-only cannot be combined." >&2
  exit 2
fi

OS_NAME="$(uname -s)"
if [[ "$OS_NAME" != "Linux" ]] && { $BUILD_DEB || $BUILD_APPIMAGE; }; then
  echo "ERROR: Linux installer flags require Linux. Current OS: $OS_NAME" >&2
  exit 2
fi

FABRIC_BRIDGE="$BUNDLED_DIR/minesport-bridge-fabric-${BRIDGE_VERSION}.jar"
FORGE_BRIDGE="$BUNDLED_DIR/minesport-bridge-forge-${BRIDGE_VERSION}.jar"
NEOFORGE_BRIDGE="$BUNDLED_DIR/minesport-bridge-neoforge-${BRIDGE_VERSION}.jar"
QUILT_BRIDGE="$BUNDLED_DIR/minesport-bridge-quilt-${BRIDGE_VERSION}.jar"

tracked_files() {
  local root="$1"
  find "$root" -type f \
    ! -path '*/.gradle/*' \
    ! -path '*/build/*' \
    ! -path '*/target/*' \
    ! -path '*/dist/*' \
    ! -path '*/.git/*' \
    -print | LC_ALL=C sort
}

tree_fingerprint() {
  local root="$1"
  {
    while IFS= read -r file; do
      local rel="${file#"$root"/}"
      printf '%s=' "$rel"
      sha256sum "$file" | awk '{print $1}'
    done < <(tracked_files "$root")
  } | sha256sum | awk '{print $1}'
}

state_get() {
  local key="$1"
  [[ -f "$STATE_FILE" ]] || return 1
  awk -F= -v key="$key" '$1 == key { sub(/^[^=]*=/, ""); print; found=1; exit } END { if (!found) exit 1 }' "$STATE_FILE"
}

state_set() {
  local key="$1" value="$2"
  mkdir -p "$(dirname "$STATE_FILE")"
  local tmp="${STATE_FILE}.tmp"
  if [[ -f "$STATE_FILE" ]]; then
    awk -F= -v key="$key" '$1 != key { print }' "$STATE_FILE" > "$tmp"
  else
    : > "$tmp"
  fi
  printf '%s=%s\n' "$key" "$value" >> "$tmp"
  LC_ALL=C sort "$tmp" > "$STATE_FILE"
  rm -f "$tmp"
}

needs_rebuild() {
  local key="$1" fingerprint="$2" output="$3"
  $FRESH && return 0
  [[ -s "$output" ]] || return 0
  local old=""
  old="$(state_get "$key" 2>/dev/null || true)"
  [[ "$old" == "$fingerprint" ]] && return 1
  return 0
}

find_engine_jar() {
  find "$ROOT/engine/build/libs" -maxdepth 1 -type f -name 'minesport-engine-*.jar' ! -name '*sources*' -print 2>/dev/null | LC_ALL=C sort | tail -n 1
}

set_embed_env() {
  local engine="$1"
  export MINESPORT_ENGINE_JAR="$engine"
  export MINESPORT_BRIDGE_JAR="$FABRIC_BRIDGE"
  export MINESPORT_BRIDGE_FABRIC_JAR="$FABRIC_BRIDGE"
  export MINESPORT_BRIDGE_FORGE_JAR="$FORGE_BRIDGE"
  export MINESPORT_BRIDGE_NEOFORGE_JAR="$NEOFORGE_BRIDGE"
  export MINESPORT_BRIDGE_QUILT_JAR="$QUILT_BRIDGE"
}

fast_check() {
  local allow_missing="${1:-false}"
  local engine
  engine="$(find_engine_jar)"
  if [[ -z "$engine" || ! -s "$FABRIC_BRIDGE" || ! -s "$FORGE_BRIDGE" || ! -s "$NEOFORGE_BRIDGE" || ! -s "$QUILT_BRIDGE" ]]; then
    if [[ "$allow_missing" == "true" ]]; then
      echo "[FAST CHECK] skipped: reusable engine/Bridge artifacts do not exist yet."
      return 0
    fi
    echo "ERROR: fast check needs previously built engine + four staged Bridge JARs." >&2
    echo "Run one normal build first." >&2
    return 1
  fi

  command -v cargo >/dev/null 2>&1 || { echo "ERROR: cargo not found." >&2; return 1; }
  command -v rustc >/dev/null 2>&1 || { echo "ERROR: rustc not found." >&2; return 1; }

  set_embed_env "$engine"
  echo "============================================"
  echo " Minesport FAST ERROR CHECK"
  echo "============================================"
  echo "No loader/engine Gradle build."
  (
    cd "$ROOT/desktop"
    cargo check --all-targets
  )
}

if $CHECK_ONLY; then
  fast_check false
  exit 0
fi

echo "============================================"
echo " Minesport $VERSION Smart Build"
echo "============================================"
echo "Target: ${OS_NAME} / $(uname -m)"
echo "Desktop: Rust + Slint 1.17.1"
echo "Mode: content-aware incremental build"
$FRESH && echo "Fresh: YES (outputs only; caches preserved)"
$DESKTOP_ONLY && echo "Desktop-only: YES"
echo

echo "[0/3] FAST ERROR CHECK..."
fast_check true
echo

if $FRESH; then
  echo "[FRESH] Removing Minesport build outputs only..."
  rm -rf \
    "$ROOT/minesport-bridge-fabric/build" \
    "$ROOT/minesport-bridge-forge/build" \
    "$ROOT/minesport-bridge-neoforge/build" \
    "$ROOT/minesport-bridge-quilt/build" \
    "$ROOT/engine/build" \
    "$ROOT/desktop/target" \
    "$ROOT/desktop/dist" \
    "$ROOT/dist/bundled-bridge" \
    "$ROOT/dist/source" \
    "$ROOT/dist/installer"
  rm -f "$STATE_FILE"
  echo "Preserved: project .gradle caches, ~/.gradle, Cargo caches, downloaded JDKs."
  echo
fi

mkdir -p "$BUNDLED_DIR"

build_bridge() {
  local label="$1" slug="$2" project="$3" destination="$4"
  local root="$ROOT/$project"
  local fingerprint
  fingerprint="$(tree_fingerprint "$root")"
  if ! needs_rebuild "bridge-$slug" "$fingerprint" "$destination"; then
    echo "  -> $label: unchanged, SKIP Gradle"
    return
  fi

  echo "  -> $label: changed/missing, building..."
  (
    cd "$root"
    chmod +x gradlew
    ./gradlew --no-daemon --stacktrace build
  )
  local jar
  jar="$(find "$root/build/libs" -maxdepth 1 -type f -name '*.jar' ! -name '*sources*' ! -name '*javadoc*' -print -quit)"
  [[ -n "$jar" ]] || { echo "ERROR: $label Bridge JAR missing." >&2; exit 1; }
  cp "$jar" "$destination"
  [[ -s "$destination" ]] || { echo "ERROR: staged $label Bridge is empty." >&2; exit 1; }
  state_set "bridge-$slug" "$fingerprint"
}

if ! $DESKTOP_ONLY; then
  echo "[1/3] Bundled Minecraft 1.21.10 loader Bridges..."
  build_bridge Fabric fabric minesport-bridge-fabric "$FABRIC_BRIDGE"
  build_bridge Forge forge minesport-bridge-forge "$FORGE_BRIDGE"
  build_bridge NeoForge neoforge minesport-bridge-neoforge "$NEOFORGE_BRIDGE"
  build_bridge Quilt quilt minesport-bridge-quilt "$QUILT_BRIDGE"
  echo
else
  echo "[1/3] Reusing bundled Minecraft loader Bridges..."
  for spec in \
    "fabric:minesport-bridge-fabric:$FABRIC_BRIDGE" \
    "forge:minesport-bridge-forge:$FORGE_BRIDGE" \
    "neoforge:minesport-bridge-neoforge:$NEOFORGE_BRIDGE" \
    "quilt:minesport-bridge-quilt:$QUILT_BRIDGE"; do
    IFS=: read -r slug project jar <<<"$spec"
    [[ -s "$jar" ]] || { echo "ERROR: --desktop-only requires $jar" >&2; exit 1; }
    state_set "bridge-$slug" "$(tree_fingerprint "$ROOT/$project")"
    echo "  $slug: $jar"
  done
  echo
fi

ENGINE_JAR="$(find_engine_jar)"
ENGINE_FP="$(tree_fingerprint "$ROOT/engine")"
if ! $DESKTOP_ONLY; then
  echo "[2/3] Java engine..."
  if [[ -z "$ENGINE_JAR" ]] || needs_rebuild engine "$ENGINE_FP" "$ENGINE_JAR"; then
    echo "  -> changed/missing, building..."
    (
      cd "$ROOT/engine"
      chmod +x gradlew
      ./gradlew --no-daemon --stacktrace build
    )
    ENGINE_JAR="$(find_engine_jar)"
    [[ -n "$ENGINE_JAR" && -s "$ENGINE_JAR" ]] || { echo "ERROR: engine JAR missing." >&2; exit 1; }
    state_set engine "$ENGINE_FP"
  else
    echo "  -> unchanged, SKIP Gradle"
  fi
  echo "  Engine: $ENGINE_JAR"
  echo
else
  echo "[2/3] Reusing Java engine..."
  [[ -n "$ENGINE_JAR" && -s "$ENGINE_JAR" ]] || { echo "ERROR: --desktop-only requires engine JAR." >&2; exit 1; }
  state_set engine "$ENGINE_FP"
  echo "  Engine: $ENGINE_JAR"
  echo
fi

echo "[3/3] Rust + Slint desktop..."
command -v cargo >/dev/null 2>&1 || { echo "ERROR: cargo is required." >&2; exit 1; }
command -v rustc >/dev/null 2>&1 || { echo "ERROR: rustc is required." >&2; exit 1; }
set_embed_env "$ENGINE_JAR"

DESKTOP_OUT="$ROOT/desktop/dist/minesport"
DESKTOP_FP="$(
  {
    printf 'desktop=%s\n' "$(tree_fingerprint "$ROOT/desktop")"
    printf 'doc=%s\n' "$(tree_fingerprint "$ROOT/doc")"
    for file in "$ENGINE_JAR" "$FABRIC_BRIDGE" "$FORGE_BRIDGE" "$NEOFORGE_BRIDGE" "$QUILT_BRIDGE"; do
      printf '%s=%s\n' "$file" "$(sha256sum "$file" | awk '{print $1}')"
    done
  } | sha256sum | awk '{print $1}'
)"

if ! needs_rebuild desktop "$DESKTOP_FP" "$DESKTOP_OUT"; then
  echo "  -> desktop inputs unchanged, SKIP Cargo test/build"
else
  (
    cd "$ROOT/desktop"
    rustc --version
    cargo --version
    cargo test
    cargo build --release --bin minesport
  )
  [[ -x "$ROOT/desktop/target/release/minesport" ]] || { echo "ERROR: Rust binary missing." >&2; exit 1; }
  mkdir -p "$ROOT/desktop/dist"
  cp "$ROOT/desktop/target/release/minesport" "$DESKTOP_OUT"
  state_set desktop "$DESKTOP_FP"
fi

mkdir -p "$ROOT/dist/source"
cp "$DESKTOP_OUT" "$ROOT/dist/source/minesport"

if $BUILD_DEB; then
  chmod +x "$ROOT/installer/linux/build-deb.sh"
  MINESPORT_VERSION="$VERSION" "$ROOT/installer/linux/build-deb.sh"
fi
if $BUILD_APPIMAGE; then
  chmod +x "$ROOT/installer/linux/build-appimage.sh"
  MINESPORT_VERSION="$VERSION" "$ROOT/installer/linux/build-appimage.sh"
fi

echo
echo "============================================"
echo " Build complete!"
echo "============================================"
echo " desktop/dist/minesport"
echo " dist/source/minesport"
$BUILD_DEB && echo " dist/installer/Minesport-*.deb"
$BUILD_APPIMAGE && echo " dist/installer/Minesport-*.AppImage"
echo
echo "Fast check:    ./build.sh --check"
echo "Force rebuild: ./build.sh --fresh"
