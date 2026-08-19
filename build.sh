#!/bin/bash
set -euo pipefail

echo "============================================"
echo " Minesport Build Script"
echo "============================================"
echo

echo "Target: $(uname -s) / $(uname -m)"
echo

# ── Fabric bridge mod ─────────────────────────
echo "[1/3] Building Fabric bridge mod..."
cd bridge
chmod +x gradlew
./gradlew jar
cd ..
echo "Bridge mod built: bridge/build/libs/"
echo

# ── Java engine ──────────────────────────────
echo "[2/3] Building Java engine..."
cd engine
chmod +x gradlew
./gradlew jar
cp build/libs/minesport-engine-*.jar ../wrapper/
cd ..
echo "Java engine built: engine/build/libs/"
echo

# ── Go wrapper ───────────────────────────────
echo "[3/3] Building Go wrapper..."
cd wrapper
echo "  -> go mod tidy..."
go mod tidy

if ! command -v cc >/dev/null 2>&1 && ! command -v gcc >/dev/null 2>&1 && [ -z "${CC:-}" ]; then
    echo
    echo "ERROR: No C compiler found. Fyne requires CGO for its desktop backend."
    if [[ "${OSTYPE:-}" == "darwin"* ]]; then
        echo "  Install Apple's command line tools: xcode-select --install"
    elif command -v apt-get >/dev/null 2>&1; then
        echo "  Install build tools: sudo apt install build-essential"
    else
        echo "  Install a native C compiler for your distribution."
    fi
    exit 1
fi

# Release build. Windows uses build.bat so it can use the GUI subsystem.
go build -trimpath -ldflags="-s -w" -o minesport .
cd ..
echo

echo "============================================"
echo " Build complete!"
echo "============================================"
echo " Executables:"
echo "   wrapper/minesport              (Go UI)"
echo "   wrapper/minesport-engine-*.jar (Java engine)"
echo "   bridge/build/libs/*.jar        (Bridge mod)"
echo
if [[ "${OSTYPE:-}" == "darwin"* ]]; then
    echo " macOS runtime note: install Java 22+ and keep the engine JAR beside minesport."
elif [[ "${OSTYPE:-}" == "linux-gnu"* ]]; then
    echo " Linux runtime note: install Java 22+ and zenity for native file/folder pickers."
fi
