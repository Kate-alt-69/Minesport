from pathlib import Path
import runpy

helper = Path('.github/patches/apply-atomic-export-bundle.py')
text = helper.read_text(encoding='utf-8')

old = '''text = replace_once(
    text,
    '''                    outFile,
                    mode,
                    optimize,
                    (doneCount, total) -> {''',
    '''                    exportFile,
                    mode,
                    optimize,
                    (doneCount, total) -> {''',
    "IPC glTF staged target",
)'''
new = '''text = replace_once(
    text,
    '''                stats = new GltfExporter(chain).export(
                    allBlocks,
                    geometryBuilder,
                    outFile,
                    mode,
                    optimize,
                    (doneCount, total) -> {''',
    '''                stats = new GltfExporter(chain).export(
                    allBlocks,
                    geometryBuilder,
                    exportFile,
                    mode,
                    optimize,
                    (doneCount, total) -> {''',
    "IPC glTF staged target",
)'''
if old not in text:
    raise SystemExit('atomic export glTF helper anchor missing')
text = text.replace(old, new, 1)

old = '''        published = true;
        cleanupTree(backupRoot);
        cleanupTree(stagingRoot);'''
new = '''        published = true;
        cleanupTreeBestEffort(backupRoot);
        cleanupTreeBestEffort(stagingRoot);'''
if old not in text:
    raise SystemExit('atomic export post-publish cleanup anchor missing')
text = text.replace(old, new, 1)

old = '''    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("Export bundle is already closed");'''
new = '''    private static void cleanupTreeBestEffort(Path root) {
        try {
            cleanupTree(root);
        } catch (IOException ignored) {
            // Publication is already committed. Scratch cleanup must never turn
            // a valid export into a reported failure.
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("Export bundle is already closed");'''
if old not in text:
    raise SystemExit('atomic export cleanup helper insertion anchor missing')
text = text.replace(old, new, 1)

temporary = Path('/tmp/minesport-atomic-export-bundle-patched.py')
temporary.write_text(text, encoding='utf-8')
runpy.run_path(str(temporary), run_name='__main__')
