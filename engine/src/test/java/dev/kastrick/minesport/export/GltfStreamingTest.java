package dev.kastrick.minesport.export;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kastrick.minesport.region.BlockData;
import dev.kastrick.minesport.resolver.ResolverChain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class GltfStreamingTest {
    @TempDir Path temp;

    @Test
    void primitiveBoundariesPreservePositionsUvsNormalsAndTriangleIndices() throws Exception {
        for (boolean weld : new boolean[]{false, true}) {
            Path gltf = temp.resolve("boundary-" + weld + ".gltf");
            exportFixture(gltf, 4097, weld);
            JsonObject root = read(gltf);
            byte[] bytes = Files.readAllBytes(gltf.resolveSibling("boundary-" + weld + ".bin"));
            assertEquals(bytes.length, root.getAsJsonArray("buffers").get(0)
                .getAsJsonObject().get("byteLength").getAsLong());
            var meshes = root.getAsJsonArray("meshes");
            assertEquals(1, meshes.size(), "primitive batching must not split logical objects");
            var primitives = meshes.get(0).getAsJsonObject().getAsJsonArray("primitives");
            assertEquals(2, primitives.size());
            int triangleIndices = 0;
            for (var element : primitives) {
                JsonObject primitive = element.getAsJsonObject();
                JsonObject attributes = primitive.getAsJsonObject("attributes");
                JsonObject positions = accessor(root, attributes.get("POSITION").getAsInt());
                JsonObject normals = accessor(root, attributes.get("NORMAL").getAsInt());
                JsonObject uvs = accessor(root, attributes.get("TEXCOORD_0").getAsInt());
                JsonObject indices = accessor(root, primitive.get("indices").getAsInt());
                int vertexCount = positions.get("count").getAsInt();
                assertEquals(vertexCount, normals.get("count").getAsInt());
                assertEquals(vertexCount, uvs.get("count").getAsInt());
                ByteBuffer p = data(root, positions, bytes);
                ByteBuffer n = data(root, normals, bytes);
                ByteBuffer uv = data(root, uvs, bytes);
                Quad quad = fixtureQuad();
                for (int vertex = 0; vertex < vertexCount; vertex++) {
                    float[] expected = quad.verts()[vertex % 4];
                    for (int axis = 0; axis < 3; axis++) {
                        assertEquals(expected[axis] - .5f, p.getFloat(), 0f);
                        assertEquals(quad.normal()[axis], n.getFloat(), 0f);
                    }
                    assertEquals(quad.vertexUVs()[vertex % 4][0], uv.getFloat(), 0f);
                    assertEquals(quad.vertexUVs()[vertex % 4][1], uv.getFloat(), 0f);
                }
                ByteBuffer indexData = data(root, indices, bytes);
                int count = indices.get("count").getAsInt();
                int[] corners = {0, 1, 2, 0, 2, 3};
                for (int index = 0; index < count; index++) {
                    int expected = (weld ? 0 : (index / 6) * 4) + corners[index % 6];
                    assertEquals(expected, indexData.getInt());
                    assertTrue(expected < vertexCount);
                }
                triangleIndices += count;
                assertEquals(0, primitive.get("material").getAsInt());
            }
            assertEquals(4097 * 6, triangleIndices);
        }
    }

    @Test
    void binaryLargerThanHeapExportsUnder48MiB() throws Exception {
        Path output = temp.resolve("large.gltf");
        Path log = temp.resolve("probe.log");
        String java = Path.of(System.getProperty("java.home"), "bin",
            System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        var classpath = new LinkedHashSet<String>();
        for (Class<?> type : List.of(GltfStreamingTest.class, GltfExporter.class, Gson.class)) {
            classpath.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        }
        Process child = new ProcessBuilder(java, "-Xmx48m", "-Dminesport.flatter=false",
            "-cp", String.join(File.pathSeparator, classpath), MemoryProbe.class.getName(), output.toString())
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(120, TimeUnit.SECONDS), "constrained-heap export timed out");
            assertEquals(0, child.exitValue(), Files.readString(log));
            long bytes = Files.size(temp.resolve("large.bin"));
            assertEquals(500_000L * (4 * 8 * 4 + 6 * 4), bytes);
            assertTrue(bytes > 48L * 1024 * 1024, "output must exceed the test JVM's entire heap");
            assertEquals(bytes, read(output).getAsJsonArray("buffers").get(0)
                .getAsJsonObject().get("byteLength").getAsLong());
            System.out.println(Files.readString(log));
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    public static final class MemoryProbe {
        public static void main(String[] args) throws Exception {
            System.setProperty("minesport.flatter", "false");
            exportFixture(Path.of(args[0]), 500_000, false);
            System.out.println("Exported 500000 quads / 76000000 binary bytes; max heap bytes: "
                + Runtime.getRuntime().maxMemory());
            Path status = Path.of("/proc/self/status");
            if (Files.isRegularFile(status)) {
                for (String line : Files.readAllLines(status)) {
                    if (line.startsWith("VmHWM:")) System.out.println("Probe process peak RSS: " + line);
                }
            }
        }
    }

    private static void exportFixture(Path output, int quads, boolean weld) throws Exception {
        try (ResolverChain chain = new ResolverChain()) {
            GeometryBuilder builder = new GeometryBuilder(chain) {
                @Override public List<Quad> buildBlock(BlockData block) {
                    // Repeated references isolate exporter working memory from world decoding.
                    return Collections.nCopies(quads, fixtureQuad());
                }
            };
            new GltfExporter(chain).export(
                List.of(new BlockData(0, 0, 0, "test:fixture", Map.of())), builder,
                output.toFile(), ObjExporter.ExportMode.ALL_MERGED, weld, null);
        }
    }

    private static Quad fixtureQuad() {
        return new Quad(new float[][]{{0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {0, 1, 0}},
            new float[]{0, 0, 1, 0, 1, 1, 0, 1}, "test:fixture", new float[]{0, 0, -1}, "north", -1);
    }

    private static JsonObject read(Path file) throws Exception {
        try (var reader = Files.newBufferedReader(file)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static JsonObject accessor(JsonObject root, int index) {
        return root.getAsJsonArray("accessors").get(index).getAsJsonObject();
    }

    private static ByteBuffer data(JsonObject root, JsonObject accessor, byte[] bytes) {
        JsonObject view = root.getAsJsonArray("bufferViews")
            .get(accessor.get("bufferView").getAsInt()).getAsJsonObject();
        int offset = view.get("byteOffset").getAsInt();
        int length = view.get("byteLength").getAsInt();
        assertEquals(0, offset % 4);
        assertTrue(offset >= 0 && offset + (long) length <= bytes.length);
        return ByteBuffer.wrap(bytes, offset, length).slice().order(ByteOrder.LITTLE_ENDIAN);
    }
}
