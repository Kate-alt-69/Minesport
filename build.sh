#!/bin/bash
set -euo pipefail

echo "============================================"
echo " Minesport Build Script"
echo "============================================"
echo

echo "Target: $(uname -s) / $(uname -m)"
echo

echo "[1/3] Building Fabric bridge mod..."
cd bridge
chmod +x gradlew
./gradlew jar
cd ..
echo "Bridge mod built: bridge/build/libs/"
echo

echo "[2/3] Building Java engine..."
cd engine
chmod +x gradlew
./gradlew jar
ENGINE_JAR=$(find build/libs -maxdepth 1 -type f -name 'minesport-engine-*.jar' | head -n 1)
cd ..
if [[ -z "${ENGINE_JAR}" ]]; then
    echo "ERROR: Java engine JAR was not produced!"
    exit 1
fi
echo "Java engine built: engine/${ENGINE_JAR#build/libs/}"
echo

echo "[3/3] Building Go wrapper..."
cd wrapper
echo "  -> go mod tidy..."
go mod tidy
echo "  -> embedding Java engine into Minesport..."
go run ./cmd/embed-engine -input "../engine/${ENGINE_JAR#build/libs/}" -output embedded_engine_generated.go

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

go build -tags minesport_embedded_engine -trimpath -ldflags="-s -w" -o minesport .
cd ..
echo

echo "============================================"
echo " Build complete!"
echo "============================================"
echo " wrapper/minesport              (engine embedded)"
echo " bridge/build/libs/*.jar        (Bridge mod)"
echo
echo " Run: cd wrapper && ./minesport"
echo " Java engine: ./minesport --java-e"
echo
echo " Diagnostics: wrapper/minesport.log"
echo "============================================"
