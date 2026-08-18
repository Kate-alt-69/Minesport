#!/bin/bash
set -e

echo "============================================"
echo " Minesport Build Script"
echo "============================================"
echo

# ── Fabric bridge mod ─────────────────────────
echo "[1/3] Building Fabric bridge mod..."
cd bridge
chmod +x gradlew
./gradlew jar
echo "Bridge mod built: bridge/build/libs/"
cd ..
echo

# ── Java engine ──────────────────────────────
echo "[2/3] Building Java engine..."
cd engine
chmod +x gradlew
./gradlew jar
echo "Java engine built: engine/build/libs/"
cp build/libs/minesport-engine-*.jar ../wrapper/
cd ..
echo

# ── Go wrapper ───────────────────────────────
echo "[3/3] Building Go wrapper..."
cd wrapper
echo "  -> go mod tidy..."
go mod tidy

if ! command -v cc >/dev/null 2>&1 && ! command -v gcc >/dev/null 2>&1 && [ -z "${CC:-}" ]; then
    echo
    echo "WARNING: No C compiler found on PATH."
    echo "  Fyne needs CGO + a real C compiler to build its OpenGL backend."
    if [[ "$OSTYPE" == "darwin"* ]]; then
        echo "  Fix: xcode-select --install"
    else
        echo "  Fix: sudo apt install build-essential"
    fi
    echo
fi

# Release build: strip debug symbols/DWARF from the distribution binary.
go build -trimpath -ldflags="-s -w" -o minesport .
cd ..
echo

echo "============================================"
echo " Build complete!"
echo "============================================"
echo " Executables:"
echo "   wrapper/minesport              (Go UI, stripped release build)"
echo "   wrapper/minesport-engine-*.jar (Java engine)"
echo "   bridge/build/libs/*.jar        (Bridge mod)"
echo
