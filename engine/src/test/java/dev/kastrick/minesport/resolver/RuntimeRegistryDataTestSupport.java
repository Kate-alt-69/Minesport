package dev.kastrick.minesport.resolver;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

final class RuntimeRegistryDataTestSupport {
    private static final byte[] MAGIC = "MSREGD01".getBytes(StandardCharsets.US_ASCII);

    record Quad(float[] vertices, String textureId, int face, boolean shade, int tintIndex) {}
    record Variant(Map<String, String> properties, List<Quad> quads) {}
    record Light(Map<String, String> properties, int level) {}
    record Block(String vanillaMapping, String loaderType, List<Variant> variants, List<Light> lights) {}

    private RuntimeRegistryDataTestSupport() {}

    static File write(
        String minecraftVersion,
        String fingerprint,
        Map<String, Block> blocks
    ) throws IOException {
        File file = File.createTempFile("minesport-runtime-registry-", ".data");
        file.deleteOnExit();
        try (DataOutputStream output = new DataOutputStream(
            new BufferedOutputStream(new FileOutputStream(file))
        )) {
            output.write(MAGIC);
            output.writeInt(RuntimeRegistryDataReader.SCHEMA);
            writeString(output, minecraftVersion);
            writeString(output, "0.17.2");
            writeString(output, fingerprint);
            writeString(output, "2026-08-23T00:00:00Z");
            output.writeInt(1);
            writeString(output, "example@1.0.0");

            output.writeInt(blocks.size());
            for (var blockEntry : blocks.entrySet()) {
                writeString(output, blockEntry.getKey());
                Block block = blockEntry.getValue();
                writeString(output, block.vanillaMapping());
                writeString(output, block.loaderType());

                output.writeInt(block.variants().size());
                for (Variant variant : block.variants()) {
                    writeMap(output, variant.properties());
                    output.writeInt(variant.quads().size());
                    for (Quad quad : variant.quads()) {
                        if (quad.vertices().length != 32) {
                            throw new IllegalArgumentException("test quad must contain exactly 32 floats");
                        }
                        for (float value : quad.vertices()) output.writeFloat(value);
                        writeString(output, quad.textureId());
                        output.writeInt(quad.face());
                        output.writeByte(quad.shade() ? 1 : 0);
                        output.writeInt(quad.tintIndex());
                    }
                }

                output.writeInt(block.lights().size());
                for (Light light : block.lights()) {
                    writeMap(output, light.properties());
                    output.writeInt(light.level());
                }
            }
        }
        return file;
    }

    static float[] unitNorthQuad() {
        return new float[] {
            0,0,0, 0,0,-1, 0,0,
            1,0,0, 0,0,-1, 1,0,
            1,1,0, 0,0,-1, 1,1,
            0,1,0, 0,0,-1, 0,1
        };
    }

    private static void writeMap(DataOutputStream output, Map<String, String> values) throws IOException {
        output.writeInt(values.size());
        for (var entry : values.entrySet()) {
            writeString(output, entry.getKey());
            writeString(output, entry.getValue());
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }
}
