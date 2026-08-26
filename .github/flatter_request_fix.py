from pathlib import Path

path = Path("engine/src/main/java/dev/kastrick/minesport/IpcMode.java")
text = path.read_text(encoding="utf-8")

old = '''            boolean optimize = getBoolOption(request, "optimize", false);
            boolean faceCulling = getBoolOption(request, "faceCulling", false);
            boolean hiddenBlockCulling = getBoolOption(request, "hiddenBlockCulling", false);
            boolean blenderExport = getBoolOption(request, "blenderExport", false);
'''
new = '''            boolean optimize = getBoolOption(request, "optimize", false);
            boolean faceCulling = getBoolOption(request, "faceCulling", false);
            boolean hiddenBlockCulling = getBoolOption(request, "hiddenBlockCulling", false);

            // The desktop export request is authoritative for FLATTER. The old
            // path ignored these IPC options and let FlatterSettings fall back
            // to unrelated JVM/env/legacy settings, so the UI could say ON or
            // 64x64 while the Java exporter actually ran OFF or 16x16.
            boolean flatterOptimization = getBoolOption(
                request,
                "flatterOptimization",
                FlatterSettings.enabled()
            );
            int flatterCellSize = FlatterSettings.cellSize();
            String requestedFlatterCellSize = getStringOption(request, "flatterCellSize", null);
            if (requestedFlatterCellSize != null && !requestedFlatterCellSize.isBlank()) {
                try {
                    flatterCellSize = Integer.parseInt(requestedFlatterCellSize.trim());
                } catch (NumberFormatException ignored) {
                    log("[WARN] Invalid FLATTER cell size in export request: " + requestedFlatterCellSize);
                }
            }
            flatterCellSize = FlatterSettings.normalizeCellSize(flatterCellSize);
            System.setProperty("minesport.flatter", Boolean.toString(flatterOptimization));
            System.setProperty("minesport.flatterCellSize", Integer.toString(flatterCellSize));
            log(
                "FLATTER " + (flatterOptimization ? "enabled" : "disabled")
                    + " · cell " + flatterCellSize
            );

            boolean blenderExport = getBoolOption(request, "blenderExport", false);
'''

count = text.count(old)
if count != 1:
    raise SystemExit(f"FLATTER request option marker: expected one match, found {count}")

path.write_text(text.replace(old, new, 1), encoding="utf-8")
print("Applied authoritative FLATTER request settings")
