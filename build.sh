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

# Fyne's desktop OpenGL backend needs CGO + a real C compiler. Without one,
# Go silently sets CGO_ENABLED=0 and cgo-based packages fail with a cryptic
# "build constraints exclude all Go files" instead of a clear error.
if ! command -v cc >/dev/null 2>&1 && ! command -v gcc >/dev/null 2>&1 && [ -z "${CC:-}" ]; then
    echo
    echo "WARNING: No C compiler found on PATH."
    echo "  Fyne needs CGO + a real C compiler to build its OpenGL backend."
    if [[ "$OSTYPE" == "darwin"* ]]; then
        echo "  Fix: xcode-select --install"
    else
        echo "  Fix: sudo apt install build-essential   (Debian/Ubuntu)"
        echo "   or: sudo dnf groupinstall 'Development Tools'   (Fedora)"
    fi
    echo "  Attempting the build anyway in case a compiler is configured another way..."
    echo
fi

go build -o minesport .
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
echo " To run:"
echo "   cd wrapper && ./minesport"
echo
echo " Dev mode (Java UI directly):"
echo "   cd engine && ./gradlew run"
echo "============================================"
