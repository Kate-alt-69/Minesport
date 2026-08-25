from pathlib import Path

def replace(path, old, new, count=1):
    p = Path(path)
    text = p.read_text()
    actual = text.count(old)
    if actual != count:
        raise SystemExit(f'{path}: expected {count} match(es), found {actual}: {old[:100]!r}')
    p.write_text(text.replace(old, new, count))

replace('engine/src/main/java/dev/kastrick/minesport/nbt/NbtWriter.java', 'public final class NbtWriter {\n    private NbtWriter() {}\n\n    public static void writeGzip(File file, Map<String, ?> root) throws IOException {\n', 'public final class NbtWriter {\n    private NbtWriter() {}\n\n    @FunctionalInterface\n    public interface ProgressCallback {\n        void onProgress(long done, long total);\n    }\n\n    private static final ThreadLocal<ProgressTracker> ACTIVE_PROGRESS = new ThreadLocal<>();\n\n    private static final class ProgressTracker {\n        private final ProgressCallback callback;\n        private final long total;\n        private final long interval;\n        private long done;\n        private long next;\n\n        ProgressTracker(ProgressCallback callback, long total) {\n            this.callback = callback;\n            this.total = Math.max(0L, total);\n            this.interval = Math.max(1L, this.total / 100L);\n            this.next = 1L;\n        }\n\n        void wroteLong() {\n            if (callback == null || total <= 0L) return;\n            done++;\n            if (done >= next || done >= total) {\n                callback.onProgress(Math.min(done, total), total);\n                next = done + interval;\n            }\n        }\n    }\n\n    public static void writeGzip(File file, Map<String, ?> root) throws IOException {\n        writeGzip(file, root, null);\n    }\n\n    public static void writeGzip(\n        File file,\n        Map<String, ?> root,\n        ProgressCallback callback\n    ) throws IOException {\n', 1)
replace('engine/src/main/java/dev/kastrick/minesport/nbt/NbtWriter.java', '        try (\n            var gzip = new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(file)));\n            var out = new DataOutputStream(gzip)\n        ) {\n            writeRoot(out, root);\n        }\n    }\n\n    public static byte[] writeBytes', '        ProgressTracker tracker = new ProgressTracker(callback, countLongArrayValues(root));\n        ACTIVE_PROGRESS.set(tracker);\n        try (\n            var gzip = new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(file)));\n            var out = new DataOutputStream(gzip)\n        ) {\n            writeRoot(out, root);\n        } finally {\n            ACTIVE_PROGRESS.remove();\n        }\n    }\n\n    public static byte[] writeBytes', 1)
replace('engine/src/main/java/dev/kastrick/minesport/nbt/NbtWriter.java', '            case NbtReader.TAG_LONG_ARRAY -> {\n                long[] values = (long[]) value;\n                out.writeInt(values.length);\n                for (long item : values) out.writeLong(item);\n            }', '            case NbtReader.TAG_LONG_ARRAY -> {\n                long[] values = (long[]) value;\n                out.writeInt(values.length);\n                ProgressTracker tracker = ACTIVE_PROGRESS.get();\n                for (long item : values) {\n                    out.writeLong(item);\n                    if (tracker != null) tracker.wroteLong();\n                }\n            }', 1)
replace('engine/src/main/java/dev/kastrick/minesport/nbt/NbtWriter.java', '    private static byte typeOf(Object value) throws IOException {', '    private static long countLongArrayValues(Object value) {\n        if (value == null) return 0L;\n        if (value instanceof long[] values) return values.length;\n        if (value instanceof NbtCompound parsed) {\n            return countLongArrayValues(parsed.asMapView());\n        }\n        if (value instanceof Map<?, ?> map) {\n            long total = 0L;\n            for (Object nested : map.values()) total += countLongArrayValues(nested);\n            return total;\n        }\n        if (value instanceof List<?> list) {\n            long total = 0L;\n            for (Object nested : list) total += countLongArrayValues(nested);\n            return total;\n        }\n        return 0L;\n    }\n\n    private static byte typeOf(Object value) throws IOException {', 1)
replace('engine/src/main/java/dev/kastrick/minesport/export/LitematicExporter.java', '    public static ExportStats export(\n        List<BlockData> blocks,\n        List<BlockEntityData> blockEntities,\n        List<EntityData> entities,\n        int firstX, int firstY, int firstZ,\n        int secondX, int secondY, int secondZ,\n        String name,\n        String author,\n        String description,\n        int minecraftDataVersion,\n        File output\n    ) throws IOException {\n', '    public static ExportStats export(\n        List<BlockData> blocks,\n        List<BlockEntityData> blockEntities,\n        List<EntityData> entities,\n        int firstX, int firstY, int firstZ,\n        int secondX, int secondY, int secondZ,\n        String name,\n        String author,\n        String description,\n        int minecraftDataVersion,\n        File output\n    ) throws IOException {\n        return export(\n            blocks, blockEntities, entities,\n            firstX, firstY, firstZ,\n            secondX, secondY, secondZ,\n            name, author, description,\n            minecraftDataVersion, output, null\n        );\n    }\n\n    public static ExportStats export(\n        List<BlockData> blocks,\n        List<BlockEntityData> blockEntities,\n        List<EntityData> entities,\n        int firstX, int firstY, int firstZ,\n        int secondX, int secondY, int secondZ,\n        String name,\n        String author,\n        String description,\n        int minecraftDataVersion,\n        File output,\n        NbtWriter.ProgressCallback writeProgress\n    ) throws IOException {\n', 1)
replace('engine/src/main/java/dev/kastrick/minesport/export/LitematicExporter.java', '        NbtWriter.writeGzip(output, root);', '        NbtWriter.writeGzip(output, root, writeProgress);', 1)
replace('engine/src/main/java/dev/kastrick/minesport/IpcMode.java', 'import java.nio.file.Files;', 'import java.nio.file.*;', 1)
replace('engine/src/main/java/dev/kastrick/minesport/IpcMode.java', '        File tempDir = null;\n        try {', '        File tempDir = null;\n        File stagedOutput = null;\n        try {', 1)
replace('engine/src/main/java/dev/kastrick/minesport/IpcMode.java', '                LitematicExporter.ExportStats schematicStats = LitematicExporter.export(\n                    allBlocks,\n                    allBlockEntities,\n                    allEntities,\n                    minX, minY, minZ,\n                    maxX, maxY, maxZ,\n                    schematicName,\n                    "Minesport",\n                    "Exported by Minesport from Minecraft " + mcVersion,\n                    dataVersion,\n                    outFile\n                );\n                progress(100, "Done");', '                Path outputParent = outFile.toPath().toAbsolutePath().getParent();\n                if (outputParent == null) outputParent = Path.of(".").toAbsolutePath();\n                stagedOutput = Files.createTempFile(\n                    outputParent,\n                    "." + outFile.getName() + ".",\n                    ".part"\n                ).toFile();\n                LitematicExporter.ExportStats schematicStats = LitematicExporter.export(\n                    allBlocks,\n                    allBlockEntities,\n                    allEntities,\n                    minX, minY, minZ,\n                    maxX, maxY, maxZ,\n                    schematicName,\n                    "Minesport",\n                    "Exported by Minesport from Minecraft " + mcVersion,\n                    dataVersion,\n                    stagedOutput,\n                    IpcMode::reportLitematicWriteProgress\n                );\n                progressIndeterminate("Cleaning temporary world data");\n                WorldCopier.cleanupTemp(tempDir);\n                tempDir = null;\n                commitStagedOutput(stagedOutput, outFile);\n                stagedOutput = null;\n                progress(99, "Finalizing export");', 1)
replace('engine/src/main/java/dev/kastrick/minesport/IpcMode.java', '        } finally {\n            if (tempDir != null) WorldCopier.cleanupTemp(tempDir);\n        }\n    }\n\n    private static int readMinecraftDataVersion', '        } finally {\n            if (stagedOutput != null) {\n                try {\n                    Files.deleteIfExists(stagedOutput.toPath());\n                } catch (IOException ignored) {}\n            }\n            if (tempDir != null) WorldCopier.cleanupTemp(tempDir);\n        }\n    }\n\n    private static void commitStagedOutput(File staged, File output) throws IOException {\n        try {\n            Files.move(\n                staged.toPath(),\n                output.toPath(),\n                StandardCopyOption.REPLACE_EXISTING,\n                StandardCopyOption.ATOMIC_MOVE\n            );\n        } catch (AtomicMoveNotSupportedException ignored) {\n            Files.move(\n                staged.toPath(),\n                output.toPath(),\n                StandardCopyOption.REPLACE_EXISTING\n            );\n        }\n    }\n\n    private static int readMinecraftDataVersion', 1)
replace('engine/src/main/java/dev/kastrick/minesport/IpcMode.java', '    private static void done(String outputPath, ObjExporter.ExportStats stats) {', '    private static void reportLitematicWriteProgress(long done, long total) {\n        if (total <= 0L) {\n            progressIndeterminate("Writing Litematica file");\n            return;\n        }\n        long interval = Math.max(1L, total / 100L);\n        if (done <= 1L || done >= total || done % interval == 0L) {\n            int percent = (int)Math.round(done * 99.0 / total);\n            progress(\n                Math.max(1, Math.min(99, percent)),\n                "Writing Litematica · " + done + "/" + total + " state words"\n            );\n        }\n    }\n\n    private static void done(String outputPath, ObjExporter.ExportStats stats) {', 1)
replace('desktop/ui/workbench-v3.slint', '    in-out property <float> task-progress: 0.0;\n    in-out property <bool> task-active: false;\n    in-out property <string> diagnostics:', '    in-out property <float> task-progress: 0.0;\n    in-out property <bool> task-active: false;\n    in-out property <bool> task-completing: false;\n    in-out property <string> task-completion-detail: "";\n    in-out property <string> diagnostics:', 1)
replace('desktop/ui/workbench-v3.slint', '                                active: root.task-active && root.task-title == "EXPORT";\n                                visible: (root.task-active && root.task-title == "EXPORT") || self.finishing;\n                                title: root.task-active && root.task-title == "EXPORT" ? "Exporting world…" : "Export complete";\n                                detail: root.task-detail;\n                                progress: root.task-progress;\n                                determinate: true;\n                                compact: false;\n                            }', '                                active: root.task-active && root.task-title == "EXPORT";\n                                complete-requested: root.task-completing;\n                                visible: (root.task-active && root.task-title == "EXPORT") || self.finishing;\n                                title: root.task-completing ? "Finishing export…" : "Exporting world…";\n                                detail: root.task-detail;\n                                progress: root.task-progress;\n                                determinate: root.task-progress > 0.02;\n                                compact: false;\n                                finished => {\n                                    if (root.task-completing) {\n                                        root.task-completing = false;\n                                        root.task-active = false;\n                                        root.task-progress = 1.0;\n                                        root.task-title = "EXPORT COMPLETE";\n                                        root.task-detail = root.task-completion-detail;\n                                    }\n                                }\n                            }', 1)
replace('desktop/ui/workbench-v3.slint', '            active: root.task-active && root.task-title != "EXPORT";\n            visible: (root.task-active && root.task-title != "EXPORT") || self.finishing;\n            title: root.task-title;', '            active: root.task-active && root.task-title != "EXPORT";\n            complete-requested: false;\n            visible: root.task-active && root.task-title != "EXPORT";\n            title: root.task-title;', 1)
replace('desktop/src/app.rs', 'fn send_export_now(ui: &MainWindow, engine: &JavaEngine, request: Value, output: &Path) {\n    ui.set_task_active(true);\n    ui.set_task_progress(0.01);\n    ui.set_task_title("EXPORT".into());', 'fn send_export_now(ui: &MainWindow, engine: &JavaEngine, request: Value, output: &Path) {\n    ui.set_task_completing(false);\n    ui.set_task_completion_detail("".into());\n    ui.set_task_active(true);\n    ui.set_task_progress(0.01);\n    ui.set_task_title("EXPORT".into());', 1)
replace('desktop/src/app.rs', '        "progress" => {\n            ui.set_task_active(true);\n            ui.set_task_progress((response.percent.clamp(0, 100) as f32) / 100.0);\n            ui.set_task_detail(response.message.into());\n        }\n        "done" => {\n            ui.set_task_active(false);\n            ui.set_task_progress(1.0);\n            ui.set_task_title("EXPORT COMPLETE".into());\n            ui.set_task_detail(format!("{} · {} blocks · {} faces · {} vertices", response.output, response.block_count, response.quad_count, response.vertex_count).into());\n            append_diagnostic(&ui, &format!("IPC <- done · {}", response.output));\n        }\n        "error" => {\n            ui.set_task_active(false);', '        "progress" => {\n            ui.set_task_active(true);\n            let progress = (response.percent.clamp(0, 100) as f32) / 100.0;\n            ui.set_task_progress(if ui.get_task_title() == "EXPORT" { progress.min(0.99) } else { progress });\n            ui.set_task_detail(response.message.into());\n        }\n        "done" => {\n            let detail = format!(\n                "{} · {} blocks · {} faces · {} vertices",\n                response.output,\n                response.block_count,\n                response.quad_count,\n                response.vertex_count\n            );\n            ui.set_task_active(true);\n            ui.set_task_progress(0.99);\n            ui.set_task_title("EXPORT".into());\n            ui.set_task_detail("Backend complete · finishing animation".into());\n            ui.set_task_completion_detail(detail.into());\n            ui.set_task_completing(true);\n            append_diagnostic(&ui, &format!("IPC <- done · {}", response.output));\n        }\n        "error" => {\n            ui.set_task_completing(false);\n            ui.set_task_active(false);', 1)
replace('desktop/src/app.rs', '                Ok(()) => {\n                    ui.set_task_active(true);\n                    ui.set_task_progress(0.02);\n                    ui.set_task_title("EXPORT".into());', '                Ok(()) => {\n                    ui.set_task_completing(false);\n                    ui.set_task_completion_detail("".into());\n                    ui.set_task_active(true);\n                    ui.set_task_progress(0.02);\n                    ui.set_task_title("EXPORT".into());', 1)
Path('desktop/ui/minesport-loader.slint').write_text('''import { ProgressIndicator } from "std-widgets.slint";

// Minesport branded activity loader.
//
// Working loop:
//   center dough -> 9 dots peel off one-by-one -> spaced rollercoaster orbit ->
//   leading dot dives into the core and the remaining dots follow the same path
//   one-by-one -> the rebuilt core tries/fails to pop -> repeat.
//
// Completion:
//   backend completion is ARMED, never hard-cuts the current loop. The dots
//   finish crashing into the core, progress stays at 99%, then the core finally
//   succeeds: it balloons into a Creeper face and only then fires finished().
//   The Creeper face never appears during ordinary loading.
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

    private property <float> cycle: 0.82;
    private property <float> success: 0.0;
    private property <bool> was-active: root.active;
    private property <bool> completion-armed: false;

    min-width: root.compact ? 240px : 340px;
    preferred-width: root.compact ? 330px : 460px;
    min-height: root.compact ? 76px : 106px;
    preferred-height: root.compact ? 82px : 120px;
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
            root.cycle = 0.82;
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
            if (root.completion-armed && root.cycle >= 0.80) {
                root.finishing = true;
                root.success = 0.0;
                return;
            }
            root.cycle = mod(root.cycle + 0.0048, 1.0);
        }
    }

    success-timer := Timer {
        interval: 16ms;
        running: root.finishing;
        triggered() => {
            root.success = min(1.0, root.success + 0.020);
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

    function peel-progress(index: int) -> float {
        if (root.cycle >= 0.28) { return 1.0; }
        let start = index * 0.018;
        return root.smooth((root.cycle - start) / 0.105);
    }

    function crash-progress(index: int) -> float {
        if (root.cycle < 0.56) { return 0.0; }
        if (root.cycle >= 0.82) { return 1.0; }
        let start = 0.56 + index * 0.018;
        return root.smooth((root.cycle - start) / 0.105);
    }

    function dot-visible(index: int) -> float {
        if (root.finishing) { return 0.0; }
        return root.peel-progress(index) * (1.0 - root.crash-progress(index));
    }

    function orbit-progress(index: int) -> float {
        let crash = root.crash-progress(index);
        if (crash > 0.0) {
            return 1.0 - crash;
        }
        let travel = root.clamp01((root.cycle - 0.12 - index * 0.006) / 0.48);
        let eased = root.smooth(travel);
        let top-bunch = sin(eased * 180deg) * 0.10;
        return root.clamp01(eased - top-bunch);
    }

    function orbit-angle(index: int) -> angle {
        let track = root.orbit-progress(index);
        return -92deg + track * 335deg;
    }

    function track-radius(index: int) -> length {
        let angle = root.orbit-angle(index);
        return 21px + (1.0 - abs(cos(angle))) * 3px;
    }

    function dot-x(index: int) -> length {
        if (root.finishing) { return 32px; }
        let p = root.dot-visible(index);
        let angle = root.orbit-angle(index);
        let orbit-x = 32px + cos(angle) * root.track-radius(index);
        return 32px + (orbit-x - 32px) * p;
    }

    function dot-y(index: int) -> length {
        if (root.finishing) { return 32px; }
        let p = root.dot-visible(index);
        let angle = root.orbit-angle(index);
        let orbit-y = 32px + sin(angle) * root.track-radius(index);
        return 32px + (orbit-y - 32px) * p;
    }

    function dot-size(index: int) -> length {
        return 5px + 1px * root.dot-visible(index);
    }

    function core-fill() -> float {
        if (root.finishing) { return 1.0; }
        let peeled = 0.0
            + root.peel-progress(0) + root.peel-progress(1) + root.peel-progress(2)
            + root.peel-progress(3) + root.peel-progress(4) + root.peel-progress(5)
            + root.peel-progress(6) + root.peel-progress(7) + root.peel-progress(8);
        let crashed = 0.0
            + root.crash-progress(0) + root.crash-progress(1) + root.crash-progress(2)
            + root.crash-progress(3) + root.crash-progress(4) + root.crash-progress(5)
            + root.crash-progress(6) + root.crash-progress(7) + root.crash-progress(8);
        return root.clamp01(1.0 - peeled / 9.0 + crashed / 9.0);
    }

    function failed-pop() -> float {
        if (root.finishing || root.cycle < 0.82) { return 0.0; }
        let t = root.clamp01((root.cycle - 0.82) / 0.18);
        return sin(t * 180deg);
    }

    function core-size() -> length {
        if (root.finishing) {
            let t = root.success;
            if (t < 0.34) {
                return 28px + root.smooth(t / 0.34) * 20px;
            }
            return 48px - root.smooth((t - 0.34) / 0.30) * 8px;
        }
        return 8px + root.core-fill() * 22px + root.failed-pop() * 4px;
    }

    function core-radius() -> length {
        if (!root.finishing) { return root.core-size() / 2; }
        let square = root.smooth((root.success - 0.20) / 0.30);
        return root.core-size() / 2 * (1.0 - square) + 6px * square;
    }

    function face-opacity() -> float {
        if (!root.finishing) { return 0.0; }
        return root.smooth((root.success - 0.32) / 0.18);
    }

    HorizontalLayout {
        padding: root.compact ? 10px : 14px;
        spacing: root.compact ? 10px : 14px;
        cross-axis-alignment: center;

        indicator := Rectangle {
            width: 64px;
            height: 64px;
            background: transparent;

            core := Rectangle {
                width: root.core-size();
                height: self.width;
                x: 32px - self.width / 2;
                y: 32px - self.height / 2;
                border-radius: root.core-radius();
                background: #f2f4f3;
                visible: self.width > 1px;

                Rectangle {
                    x: self.width * 0.18;
                    y: self.height * 0.22;
                    width: self.width * 0.22;
                    height: self.height * 0.22;
                    background: #080a09;
                    opacity: root.face-opacity();
                }
                Rectangle {
                    x: self.width * 0.60;
                    y: self.height * 0.22;
                    width: self.width * 0.22;
                    height: self.height * 0.22;
                    background: #080a09;
                    opacity: root.face-opacity();
                }
                Rectangle {
                    x: self.width * 0.39;
                    y: self.height * 0.43;
                    width: self.width * 0.22;
                    height: self.height * 0.18;
                    background: #080a09;
                    opacity: root.face-opacity();
                }
                Rectangle {
                    x: self.width * 0.28;
                    y: self.height * 0.56;
                    width: self.width * 0.44;
                    height: self.height * 0.20;
                    background: #080a09;
                    opacity: root.face-opacity();
                }
                Rectangle {
                    x: self.width * 0.28;
                    y: self.height * 0.70;
                    width: self.width * 0.15;
                    height: self.height * 0.16;
                    background: #080a09;
                    opacity: root.face-opacity();
                }
                Rectangle {
                    x: self.width * 0.57;
                    y: self.height * 0.70;
                    width: self.width * 0.15;
                    height: self.height * 0.16;
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
                opacity: root.dot-visible(index);
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
print('Applied Litematica lifecycle and loader rewrite.')
