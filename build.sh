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
    --build-installer-exe|--build-installer-nsis|--build-installer-inno|--build-installer-msi)
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

newest_input_epoch() {
  local root="$1"
  local newest=0
  while IFS= read -r file; do
    local stamp
    stamp="$(stat -c %Y "$file" 2>/dev/null || echo 0)"
    (( stamp > newest )) && newest=$stamp
  done < <(tracked_files "$root")
  printf '%s\n' "$newest"
}

needs_rebuild() {
  local key="$1" fingerprint="$2" output="$3" source_root="$4"
  $FRESH && return 0
  [[ -f "$output" ]] || return 0
  local saved=""
  saved="$(state_get "$key" 2>/dev/null || true)"
  [[ "$saved" == "$fingerprint" ]] && return 1

  # Adopt outputs made before smart state existed when they are newer than all
  # meaningful source/config inputs. Later decisions become hash-based.
  local output_epoch newest
  output_epoch="$(stat -c %Y "$output" 2>/dev/null || echo 0)"
  newest="$(newest_input_epoch "$source_root")"
  if (( output_epoch >= newest )); then
    state_set "$key" "$fingerprint"
    return 1
  fi
  return 0
}

remove_build_outputs() {
  echo '[FRESH] Removing Minesport build outputs only...'
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
    "$ROOT/dist/installer" \
    "$STATE_FILE"
  echo '  Preserved: project .gradle caches, ~/.gradle, Cargo registry/git caches, downloaded JDKs.'
  echo
}

fast_check() {
  if ! command -v cargo >/dev/null 2>&1 || ! command -v rustc >/dev/null 2>&1; then
    echo 'ERROR: Rust/Cargo is required for --check.' >&2
    return 1
  fi

  echo '============================================'
  echo ' Minesport FAST ERROR CHECK'
  echo '============================================'
  echo 'No Fabric/Forge/NeoForge/Quilt Gradle build.'
  echo 'Checking Rust + Slint + build.rs + -D warnings only.'
  echo

  (
    cd "$ROOT/desktop"
    MINESPORT_FAST_CHECK=1 cargo check --all-targets
  )
  echo
  echo 'FAST CHECK PASSED - safe to start expensive loader builds.'
}

if $CHECK_ONLY; then
  fast_check
  exit 0
fi

cd "$ROOT"

printf '%s\n' '============================================'
printf ' Minesport %s Smart Build\n' "$VERSION"
printf '%s\n' '============================================'
printf 'Target: %s / amd64\n' "$OS_NAME"
printf '%s\n' 'Desktop: Rust + Slint 1.17.1'
printf '%s\n' 'Mode: content-aware incremental build'
$FRESH && printf '%s\n' 'Fresh: YES (outputs only; caches preserved)'
$DESKTOP_ONLY && printf '%s\n' 'Desktop-only: YES'
if ! $BUILD_DEB && ! $BUILD_APPIMAGE; then
  printf '%s\n' 'Packaging: disabled (executable only)'
else
  formats=()
  $BUILD_DEB && formats+=(DEB)
  $BUILD_APPIMAGE && formats+=(AppImage)
  printf 'Packaging: %s\n' "$(IFS=' + '; echo "${formats[*]}")"
fi
printf '\n'

printf '%s\n' '[0/3] FAST ERROR CHECK...'
fast_check
printf '\n'

$FRESH && remove_build_outputs
mkdir -p "$BUNDLED_DIR"

build_bridge() {
  local name="$1" slug="$2" project="$3" output="$4"
  local project_path="$ROOT/$project"
  local fingerprint
  fingerprint="$(tree_fingerprint "$project_path")"
  local key="bridge-$slug"

  printf '  -> %s: ' "$name"
  if $DESKTOP_ONLY; then
    [[ -f "$output" ]] || { echo "missing $output" >&2; exit 1; }
    state_set "$key" "$fingerprint"
    echo 'reuse existing artifact'
    return
  fi

  if ! needs_rebuild "$key" "$fingerprint" "$output" "$project_path"; then
    echo 'unchanged, SKIP Gradle'
    return
  fi
  echo 'changed, building...'
  (
    cd "$project_path"
    ./gradlew --no-daemon build
  )
  local jar
  jar="$(find "$project_path/build/libs" -maxdepth 1 -type f -name '*.jar' ! -name '*-sources.jar' ! -name '*-dev.jar' | sort | head -n1)"
  [[ -n "$jar" && -f "$jar" ]] || { echo "ERROR: $name Bridge jar not found." >&2; exit 1; }
  cp -f "$jar" "$output"
  state_set "$key" "$fingerprint"
  printf '     staged: %s\n' "${output#$ROOT/}"
}

printf '%s\n' '[1/3] Bundled Minecraft 1.21.10 loader Bridges...'
build_bridge Fabric fabric minesport-bridge-fabric "$FABRIC_BRIDGE"
build_bridge Forge forge minesport-bridge-forge "$FORGE_BRIDGE"
build_bridge NeoForge neoforge minesport-bridge-neoforge "$NEOFORGE_BRIDGE"
build_bridge Quilt quilt minesport-bridge-quilt "$QUILT_BRIDGE"
printf '\n'

ENGINE_ROOT="$ROOT/engine"
ENGINE_JAR="$(find "$ENGINE_ROOT/build/libs" -maxdepth 1 -type f -name 'minesport-engine-*.jar' ! -name '*-sources.jar' 2>/dev/null | sort | head -n1 || true)"
ENGINE_FINGERPRINT="$(tree_fingerprint "$ENGINE_ROOT")"
printf '%s\n' '[2/3] Java engine...'
if $DESKTOP_ONLY; then
  [[ -n "$ENGINE_JAR" && -f "$ENGINE_JAR" ]] || { echo 'ERROR: --desktop-only needs a previously built engine jar.' >&2; exit 1; }
  state_set engine "$ENGINE_FINGERPRINT"
  echo '  -> reuse existing artifact'
else
  ENGINE_OUTPUT="${ENGINE_JAR:-$ENGINE_ROOT/build/libs/minesport-engine-${VERSION}.jar}"
  if needs_rebuild engine "$ENGINE_FINGERPRINT" "$ENGINE_OUTPUT" "$ENGINE_ROOT"; then
    echo '  -> changed, building...'
    (
      cd "$ENGINE_ROOT"
      ./gradlew --no-daemon build
    )
    ENGINE_JAR="$(find "$ENGINE_ROOT/build/libs" -maxdepth 1 -type f -name 'minesport-engine-*.jar' ! -name '*-sources.jar' | sort | head -n1)"
    [[ -n "$ENGINE_JAR" && -f "$ENGINE_JAR" ]] || { echo 'ERROR: Java engine jar not found.' >&2; exit 1; }
    state_set engine "$ENGINE_FINGERPRINT"
  else
    echo '  -> unchanged, SKIP Gradle'
    ENGINE_JAR="$ENGINE_OUTPUT"
  fi
fi
printf '     engine: %s\n\n' "${ENGINE_JAR#$ROOT/}"

export MINESPORT_ENGINE_JAR="$ENGINE_JAR"
export MINESPORT_BRIDGE_JAR="$FABRIC_BRIDGE"
export MINESPORT_BRIDGE_FABRIC_JAR="$FABRIC_BRIDGE"
export MINESPORT_BRIDGE_FORGE_JAR="$FORGE_BRIDGE"
export MINESPORT_BRIDGE_NEOFORGE_JAR="$NEOFORGE_BRIDGE"
export MINESPORT_BRIDGE_QUILT_JAR="$QUILT_BRIDGE"

printf '%s\n' '[3/3] Rust + Slint desktop...'
(
  cd "$ROOT/desktop"
  cargo test --all-targets
  cargo build --release
)

mkdir -p "$ROOT/dist"
cp -f "$ROOT/desktop/target/release/minesport" "$ROOT/dist/minesport"
printf 'Desktop built: %s\n' "$ROOT/dist/minesport"

if $BUILD_DEB; then
  "$ROOT/installer/linux/build-deb.sh" "$ROOT/dist/minesport"
fi
if $BUILD_APPIMAGE; then
  "$ROOT/installer/linux/build-appimage.sh" "$ROOT/dist/minesport"
fi

printf '\n%s\n' 'BUILD COMPLETE'
