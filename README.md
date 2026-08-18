# Minesport
**Minecraft world exporter by Kastrick**

Exports vanilla + modded Minecraft worlds to OBJ/glTF for Blender.

## Features
- Safe world copy to temp — never touches original files
- Reads .mca region files (1.18+ format)
- Full NBT parser
- Pass 1: block extraction with properties
- Pass 2: multipart connection resolver (fences, walls, panes)
- Vanilla asset resolver — reads JSON models from minecraft.jar
- Fabric mod resolver — scans mod jars for modded block geometry
- OBJ export (grouped/merged/individual)
- glTF 2.0 export with embedded textures (recommended for Blender)
- Go/Fyne native UI with IPC bridge to Java engine

## Building

### Requirements
- Java 22+
- Go 1.26+
- Gradle wrapper files in engine/ (gradlew, gradlew.bat, gradle/wrapper/)

### Windows
```
build.bat
```

### Linux/Mac
```
chmod +x build.sh && ./build.sh
```

### Manual
```bash
cd engine && ./gradlew jar
cd wrapper && go mod tidy && go build -o minesport .
```

## Running
```
cd wrapper && ./minesport         # Go UI (recommended)
cd engine && ./gradlew run        # Java UI (dev mode)
```

## Architecture
```
engine/   Java core — NBT, region reader, model resolver, OBJ/glTF export
wrapper/  Go UI — Fyne window, IPC bridge to Java engine
```

## Known limitations
- Block entities (chests/signs/banners) use fallback cube geometry
- Fluids not exported
- Biome tint not applied
- Forge resolver not yet implemented
