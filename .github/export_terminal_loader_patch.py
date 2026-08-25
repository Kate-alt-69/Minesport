from pathlib import Path


def replace(path, old, new, count=1):
    p = Path(path)
    text = p.read_text()
    actual = text.count(old)
    if actual != count:
        raise SystemExit(f"{path}: expected {count} match(es), found {actual}: {old[:120]!r}")
    p.write_text(text.replace(old, new, count))

# Once the atomic final path exists, emit the terminal response immediately.
replace(
    'engine/src/main/java/dev/kastrick/minesport/IpcMode.java',
    '''                progressIndeterminate("Cleaning temporary world data");
                WorldCopier.cleanupTemp(tempDir);
                tempDir = null;
                commitStagedOutput(stagedOutput, outFile);
                stagedOutput = null;
                progress(99, "Finalizing export");
                log(
                    "Litematica export: " + schematicStats.blockCount() + " blocks, "
                    + schematicStats.blockEntityCount() + " block entities, "
                    + schematicStats.entityCount() + " entities, "
                    + schematicStats.blockTickCount() + " block ticks, "
                    + schematicStats.fluidTickCount() + " fluid ticks, "
                    + schematicStats.paletteSize() + " palette states, "
                    + schematicStats.volume() + " volume"
                );
                done(
                    outFile.getAbsolutePath(),
                    new ObjExporter.ExportStats(schematicStats.blockCount(), 0, 0)
                );
                return;''',
    '''                progressIndeterminate("Cleaning temporary world data");
                WorldCopier.cleanupTemp(tempDir);
                tempDir = null;
                log(
                    "Litematica export: " + schematicStats.blockCount() + " blocks, "
                    + schematicStats.blockEntityCount() + " block entities, "
                    + schematicStats.entityCount() + " entities, "
                    + schematicStats.blockTickCount() + " block ticks, "
                    + schematicStats.fluidTickCount() + " fluid ticks, "
                    + schematicStats.paletteSize() + " palette states, "
                    + schematicStats.volume() + " volume"
                );
                progressIndeterminate("Publishing Litematica file");
                commitStagedOutput(stagedOutput, outFile);
                stagedOutput = null;
                // The final path now represents a complete export. Make the terminal
                // event the very next IPC message so the desktop cannot remain in an
                // earlier "Reading..." state while a finished file is visible.
                done(
                    outFile.getAbsolutePath(),
                    new ObjExporter.ExportStats(schematicStats.blockCount(), 0, 0)
                );
                return;'''
)

# Surface the exact slow sub-stage before entering expensive chunk work.
replace(
    'engine/src/main/java/dev/kastrick/minesport/region/RegionReader.java',
    '''                try {
                    raf.seek(seekPos);
                    int dataLength = raf.readInt();''',
    '''                try {
                    if (progress != null) {
                        progress.onProgress(
                            chunksProcessed,
                            totalChunks,
                            "Opening chunk " + worldChunkX + "," + worldChunkZ
                        );
                    }
                    raf.seek(seekPos);
                    int dataLength = raf.readInt();'''
)
replace(
    'engine/src/main/java/dev/kastrick/minesport/region/RegionReader.java',
    '''                    byte[] nbtBytes = decompress(compressed, compressionType);
                    if (nbtBytes == null || nbtBytes.length == 0) continue;

                    NbtCompound chunkNbt = NbtReader.readBytes(nbtBytes);
                    if (decodeBlocks) {''',
    '''                    byte[] nbtBytes = decompress(compressed, compressionType);
                    if (nbtBytes == null || nbtBytes.length == 0) continue;
                    if (progress != null) {
                        progress.onProgress(
                            chunksProcessed,
                            totalChunks,
                            "Parsing chunk " + worldChunkX + "," + worldChunkZ
                        );
                    }

                    NbtCompound chunkNbt = NbtReader.readBytes(nbtBytes);
                    if (progress != null) {
                        progress.onProgress(
                            chunksProcessed,
                            totalChunks,
                            "Decoding chunk " + worldChunkX + "," + worldChunkZ
                        );
                    }
                    if (decodeBlocks) {'''
)
replace(
    'engine/src/main/java/dev/kastrick/minesport/region/RegionReader.java',
    '''                            "Reading chunk " + worldChunkX + "," + worldChunkZ
                        );''',
    '''                            "Read chunk " + worldChunkX + "," + worldChunkZ
                        );'''
)

Path('desktop/ui/minesport-loader.slint').write_text(r'''import { ProgressIndicator } from "std-widgets.slint";

// Minesport activity animation.
//
// Working cycle:
//   - startup begins on the track: NO persistent center ball
//   - nine clearly spaced dots run one rollercoaster loop, pause at the top,
//     run a second loop, pause again, then run the final loop
//   - the final loop flows directly into a shared curved dive; dots enter that
//     curve one-by-one and crash into the center in order
//   - only the crash builds a center mass; an unfinished task makes that mass
//     fail its pop and peel back out one-by-one for the next cycle
//
// Completion:
//   backend completion arms success and progress remains at 99%. The current
//   train is allowed to reach the crash mass. Only then does the mass balloon
//   into the Creeper face. finished() fires after that reveal.
export component MinesportLoader inherits Rectangle {
    in property <bool> active: false;
    in property <bool> complete-requested: false;
    in property <string> title: "Working…";
    in property <string> detail: "";
    in property <float> progress: 0.0;
    in property <bool> determinate: false;
    in property <bool> compact: false;
    in property <bool> animate-working: true;
    out property <bool> finishing: false;
    callback finished();

    // Start on the running track so a new task never opens on a giant center ball.
    private property <float> cycle: 0.22;
    private property <float> success: 0.0;
    private property <bool> was-active: root.active;
    private property <bool> completion-armed: false;

    min-width: root.compact ? 250px : 360px;
    preferred-width: root.compact ? 340px : 480px;
    min-height: root.compact ? 84px : 118px;
    preferred-height: root.compact ? 92px : 132px;
    visible: root.active || root.finishing;
    background: #141817f2;
    border-width: 1px;
    border-color: #3b453f;
    border-radius: 9px;

    changed active => {
        if (root.active && !root.was-active) {
            root.was-active = true;
            root.finishing = false;
            root.completion-armed = false;
            root.success = 0.0;
            root.cycle = 0.22;
        } else if (!root.active && root.was-active && !root.finishing) {
            root.was-active = false;
            root.completion-armed = false;
        }
    }

    changed complete-requested => {
        if (root.complete-requested && root.active) {
            root.completion-armed = true;
        }
    }

    orbit-timer := Timer {
        interval: 16ms;
        running: root.active && root.animate-working && !root.finishing;
        triggered() => {
            // All nine dots have reached the crash mass by ~0.96.
            if (root.completion-armed && root.cycle >= 0.962) {
                root.finishing = true;
                root.success = 0.0;
                return;
            }
            root.cycle = mod(root.cycle + 0.0036, 1.0);
        }
    }

    success-timer := Timer {
        interval: 16ms;
        running: root.finishing;
        triggered() => {
            root.success = min(1.0, root.success + 0.018);
            if (root.success >= 1.0) {
                self.running = false;
                root.finishing = false;
                root.completion-armed = false;
                root.finished();
            }
        }
    }

    function clamp01(t: float) -> float {
        return max(0.0, min(1.0, t));
    }

    function smooth(t: float) -> float {
        let c = root.clamp01(t);
        return c * c * (3.0 - 2.0 * c);
    }

    // After a failed mass, dots leave it ONE BY ONE along the reverse dive and
    // join the top of the train. Startup skips this phase by beginning at 0.22.
    function peel-progress(index: int) -> float {
        if (root.cycle >= 0.18) { return 1.0; }
        let start = index * 0.011;
        return root.smooth((root.cycle - start) / 0.075);
    }

    // Coaster units: exactly two runs with a visible top pause, then a third
    // run that flows straight into the crash without a pause.
    function coaster-units() -> float {
        let c = root.cycle;
        if (c < 0.18) { return 0.0; }
        if (c < 0.38) { return root.smooth((c - 0.18) / 0.20); }
        if (c < 0.44) { return 1.0; }
        if (c < 0.64) { return 1.0 + root.smooth((c - 0.44) / 0.20); }
        if (c < 0.70) { return 2.0; }
        if (c < 0.82) { return 2.0 + root.smooth((c - 0.70) / 0.12); }
        return 3.0;
    }

    function track-progress(index: int) -> float {
        // 0.075 of a lap between dots keeps nine 6px balls visibly separated.
        return mod(root.coaster-units() - index * 0.075 + 20.0, 1.0);
    }

    function track-angle(index: int) -> angle {
        return -90deg + root.track-progress(index) * 360deg;
    }

    function track-x(index: int) -> length {
        let angle = root.track-angle(index);
        return 46px + cos(angle) * 33px;
    }

    function track-y(index: int) -> length {
        let t = root.track-progress(index);
        let angle = root.track-angle(index);
        // Ellipse + a gentle double hump = rollercoaster rather than a plain orbit.
        return 46px + sin(angle) * 28px - sin(t * 720deg) * 4px;
    }

    function crash-progress(index: int) -> float {
        if (root.cycle < 0.82) { return 0.0; }
        let start = 0.82 + index * 0.008;
        return root.smooth((root.cycle - start) / 0.075);
    }

    function crash-x(index: int) -> length {
        let p = root.crash-progress(index);
        // Every dot follows the same curved entry line, delayed one-by-one.
        return 46px + sin(p * 180deg) * 17px;
    }

    function crash-y(index: int) -> length {
        let p = root.crash-progress(index);
        return 14px + p * 32px;
    }

    function peel-x(index: int) -> length {
        let p = root.peel-progress(index);
        return 46px - sin(p * 180deg) * 17px;
    }

    function peel-y(index: int) -> length {
        let p = root.peel-progress(index);
        return 46px - p * 32px;
    }

    function dot-x(index: int) -> length {
        if (root.finishing) { return 46px; }
        if (root.cycle < 0.18) { return root.peel-x(index); }
        if (root.cycle < 0.82) { return root.track-x(index); }
        return root.crash-x(index);
    }

    function dot-y(index: int) -> length {
        if (root.finishing) { return 46px; }
        if (root.cycle < 0.18) { return root.peel-y(index); }
        if (root.cycle < 0.82) { return root.track-y(index); }
        return root.crash-y(index);
    }

    function dot-opacity(index: int) -> float {
        if (root.finishing) { return 0.0; }
        if (root.cycle < 0.18) { return root.peel-progress(index); }
        if (root.cycle < 0.82) { return 1.0; }
        return 1.0 - root.crash-progress(index);
    }

    function dot-size(index: int) -> length {
        return 6px;
    }

    function peeled-average() -> float {
        return (
            root.peel-progress(0) + root.peel-progress(1) + root.peel-progress(2)
            + root.peel-progress(3) + root.peel-progress(4) + root.peel-progress(5)
            + root.peel-progress(6) + root.peel-progress(7) + root.peel-progress(8)
        ) / 9.0;
    }

    function crashed-average() -> float {
        return (
            root.crash-progress(0) + root.crash-progress(1) + root.crash-progress(2)
            + root.crash-progress(3) + root.crash-progress(4) + root.crash-progress(5)
            + root.crash-progress(6) + root.crash-progress(7) + root.crash-progress(8)
        ) / 9.0;
    }

    function failed-pop() -> float {
        if (root.finishing || root.cycle < 0.962) { return 0.0; }
        let t = root.clamp01((root.cycle - 0.962) / 0.038);
        return sin(t * 180deg);
    }

    function working-core-size() -> length {
        // No mass at all during the coaster runs.
        if (root.cycle >= 0.18 && root.cycle < 0.82) { return 0px; }
        if (root.cycle < 0.18) {
            return (1.0 - root.peeled-average()) * 34px;
        }
        return root.crashed-average() * 34px + root.failed-pop() * 5px;
    }

    function core-size() -> length {
        if (!root.finishing) { return root.working-core-size(); }
        let t = root.success;
        if (t < 0.34) {
            return 34px + root.smooth(t / 0.34) * 22px;
        }
        return 56px - root.smooth((t - 0.34) / 0.30) * 12px;
    }

    function core-radius() -> length {
        if (!root.finishing) { return root.core-size() / 2; }
        let square = root.smooth((root.success - 0.20) / 0.30);
        return root.core-size() / 2 * (1.0 - square) + 5px * square;
    }

    function face-opacity() -> float {
        if (!root.finishing) { return 0.0; }
        return root.smooth((root.success - 0.34) / 0.17);
    }

    HorizontalLayout {
        padding: root.compact ? 10px : 14px;
        spacing: root.compact ? 10px : 16px;
        cross-axis-alignment: center;

        indicator := Rectangle {
            width: 92px;
            height: 92px;
            background: transparent;

            core := Rectangle {
                width: root.core-size();
                height: self.width;
                x: 46px - self.width / 2;
                y: 46px - self.height / 2;
                border-radius: root.core-radius();
                background: #f2f4f3;
                visible: self.width > 0.8px;

                Rectangle {
                    x: core.width * 0.18;
                    y: core.height * 0.22;
                    width: core.width * 0.22;
                    height: core.height * 0.22;
                    background: #080a09;
                    opacity: root.face-opacity();
                }
                Rectangle {
                    x: core.width * 0.60;
                    y: core.height * 0.22;
                    width: core.width * 0.22;
                    height: core.height * 0.22;
                    background: #080a09;
                    opacity: root.face-opacity();
                }
                Rectangle {
                    x: core.width * 0.39;
                    y: core.height * 0.43;
                    width: core.width * 0.22;
                    height: core.height * 0.18;
                    background: #080a09;
                    opacity: root.face-opacity();
                }
                Rectangle {
                    x: core.width * 0.28;
                    y: core.height * 0.56;
                    width: core.width * 0.44;
                    height: core.height * 0.20;
                    background: #080a09;
                    opacity: root.face-opacity();
                }
                Rectangle {
                    x: core.width * 0.28;
                    y: core.height * 0.70;
                    width: core.width * 0.15;
                    height: core.height * 0.16;
                    background: #080a09;
                    opacity: root.face-opacity();
                }
                Rectangle {
                    x: core.width * 0.57;
                    y: core.height * 0.70;
                    width: core.width * 0.15;
                    height: core.height * 0.16;
                    background: #080a09;
                    opacity: root.face-opacity();
                }
            }

            for dot[index] in 9: Rectangle {
                width: root.dot-size(index);
                height: self.width;
                x: root.dot-x(index) - self.width / 2;
                y: root.dot-y(index) - self.height / 2;
                border-radius: self.width / 2;
                background: #f2f4f3;
                opacity: root.dot-opacity(index);
                visible: self.opacity > 0.001;
            }
        }

        VerticalLayout {
            horizontal-stretch: 1;
            spacing: root.compact ? 3px : 6px;
            Text {
                text: root.title;
                color: #eef2ef;
                font-size: root.compact ? 12px : 14px;
                font-weight: 700;
                overflow: elide;
            }
            if root.detail != "": Text {
                text: root.detail;
                color: #aeb8b2;
                font-size: root.compact ? 10px : 11px;
                wrap: word-wrap;
                overflow: elide;
            }
            if root.determinate && root.progress > 0.02: HorizontalLayout {
                spacing: 8px;
                ProgressIndicator {
                    horizontal-stretch: 1;
                    progress: max(0.0, min(1.0, root.progress));
                }
                Text {
                    width: 44px;
                    text: round(max(0.0, min(1.0, root.progress)) * 100) + "%";
                    color: #d8ded9;
                    font-size: 11px;
                    font-family: "monospace";
                    font-weight: 700;
                    horizontal-alignment: right;
                    vertical-alignment: center;
                }
            }
        }
    }
}
''')

print('Applied export-terminal, chunk-stage, and loader choreography fixes.')
