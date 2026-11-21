package dev.kastrick.minesport.datapack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DataPackBlockTagReaderTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsModernAndLegacyFolderTagsAndResolvesNestedReferences() throws IOException {
        Path pack = tempDir.resolve("folder-pack");
        writeTag(pack, "data/demo/tags/block/decorative.json",
            """
            {"values":["minecraft:oak_fence","#demo:wooden"]}
            """);
        writeTag(pack, "data/demo/tags/blocks/wooden.json",
            """
            {"values":["minecraft:oak_planks"]}
            """);

        DataPackBlockTagReader reader = DataPackBlockTagReader.load(List.of(pack.toFile()), null);

        assertEquals(
            Set.of("minecraft:oak_fence", "minecraft:oak_planks"),
            reader.resolveTag("demo:decorative")
        );
        assertEquals(Set.of("demo:decorative", "demo:wooden"), reader.getTagIds());
    }

    @Test
    void laterPacksAppendUnlessReplaceIsSet() throws IOException {
        Path first = tempDir.resolve("first");
        Path second = tempDir.resolve("second");
        Path third = tempDir.resolve("third");
        writeTag(first, "data/demo/tags/block/wood.json", "{\"values\":[\"minecraft:oak_log\"]}");
        writeTag(second, "data/demo/tags/block/wood.json", "{\"values\":[\"minecraft:spruce_log\"]}");
        writeTag(third, "data/demo/tags/block/wood.json",
            "{\"replace\":true,\"values\":[\"minecraft:birch_log\"]}");

        DataPackBlockTagReader reader = DataPackBlockTagReader.load(
            List.of(first.toFile(), second.toFile(), third.toFile()),
            null
        );

        assertEquals(Set.of("minecraft:birch_log"), reader.resolveTag("demo:wood"));
    }

    @Test
    void loadsTagsFromZipPacksAndPreventsReferenceCycles() throws IOException {
        Path zip = tempDir.resolve("pack.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) {
            putZipEntry(output, "data/demo/tags/blocks/a.json",
                "{\"values\":[\"#demo:b\"]}");
            putZipEntry(output, "data/demo/tags/blocks/b.json",
                "{\"values\":[\"#demo:a\",\"minecraft:stone\"]}");
        }

        DataPackBlockTagReader reader = DataPackBlockTagReader.load(List.of(zip.toFile()), null);

        assertEquals(Set.of("minecraft:stone"), reader.resolveTag("demo:a"));
    }

    @Test
    void discoversFoldersAndZipFilesButIgnoresOtherEntries() throws IOException {
        Path world = tempDir.resolve("world");
        Path dataPacks = Files.createDirectories(world.resolve("datapacks"));
        Files.createDirectories(dataPacks.resolve("folder-pack"));
        Files.createFile(dataPacks.resolve("archive.zip"));
        Files.createFile(dataPacks.resolve("readme.txt"));

        List<File> found = DataPackBlockTagReader.discoverWorldDataPacks(world.toFile());

        assertEquals(Set.of("folder-pack", "archive.zip"),
            found.stream().map(File::getName).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void nullOrEmptyInputsProduceAnEmptyReader() {
        DataPackBlockTagReader reader = DataPackBlockTagReader.load(null, null);
        assertTrueEmpty(reader);
    }

    private static void assertTrueEmpty(DataPackBlockTagReader reader) {
        assertFalse(!reader.isEmpty());
        assertEquals(Set.of(), reader.resolveTag("demo:missing"));
    }

    private static void writeTag(Path pack, String relative, String json) throws IOException {
        Path file = pack.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }

    private static void putZipEntry(ZipOutputStream output, String name, String json) throws IOException {
        output.putNextEntry(new ZipEntry(name));
        output.write(json.getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
    }
}
