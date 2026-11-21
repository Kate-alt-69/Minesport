package dev.kastrick.minesport.model;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelParserTest {
    @Test
    void parsesVariantBlockstatesAndPrefersSpecificEntriesOverDefault() throws Exception {
        BlockState state = ModelParser.parseBlockState(json(
            """
            {
              "variants": {
                "": {"model":"minecraft:block/default"},
                "facing=north": {"model":"minecraft:block/north","y":90,"uvlock":true}
              }
            }
            """
        ));

        assertEquals(BlockState.Format.VARIANTS, state.format);
        assertEquals("minecraft:block/north",
            state.resolve(Map.of("facing", "north")).getFirst().modelPath);
        assertEquals("minecraft:block/default",
            state.resolve(Map.of("facing", "east")).getFirst().modelPath);
        assertEquals(90, state.resolve(Map.of("facing", "north")).getFirst().y);
        assertTrue(state.resolve(Map.of("facing", "north")).getFirst().uvlock);
    }

    @Test
    void weightedVariantsChooseOneStableCoordinateDependentApplication() throws Exception {
        BlockState state = ModelParser.parseBlockState(json(
            """
            {
              "variants": {
                "powered=true": [
                  {"model":"minecraft:block/weighted_a","weight":1},
                  {"model":"minecraft:block/weighted_b","weight":3}
                ]
              }
            }
            """
        ));

        List<BlockState.ModelApplication> first = state.resolve(Map.of("powered", "true"), 17, 64, -9);
        List<BlockState.ModelApplication> repeated = state.resolve(Map.of("powered", "true"), 17, 64, -9);

        assertEquals(1, first.size());
        assertEquals(first.getFirst().modelPath, repeated.getFirst().modelPath);
        assertTrue(Set.of("minecraft:block/weighted_a", "minecraft:block/weighted_b")
            .contains(first.getFirst().modelPath));
        assertTrue(state.resolve(Map.of("powered", "false"), 17, 64, -9).isEmpty());
    }

    @Test
    void parsesMultipartConditionsIncludingOrAndUnconditionalParts() throws Exception {
        BlockState state = ModelParser.parseBlockState(json(
            """
            {
              "multipart": [
                {
                  "when": {"OR":[{"facing":"north"},{"facing":"south"}]},
                  "apply": {"model":"minecraft:block/side"}
                },
                {"apply":{"model":"minecraft:block/base"}}
              ]
            }
            """
        ));

        assertEquals(BlockState.Format.MULTIPART, state.format);
        assertEquals(
            List.of("minecraft:block/side", "minecraft:block/base"),
            state.resolve(Map.of("facing", "north")).stream()
                .map(application -> application.modelPath).toList()
        );
        assertEquals(
            List.of("minecraft:block/base"),
            state.resolve(Map.of("facing", "east")).stream()
                .map(application -> application.modelPath).toList()
        );
    }

    @Test
    void parsesModelParentsTexturesElementsRotationAndFaces() throws Exception {
        BlockModel model = ModelParser.parseBlockModel(json(
            """
            {
              "parent":"block/cube_all",
              "ambientocclusion":false,
              "textures":{"all":"block/stone","side":"#all"},
              "elements":[{
                "from":[0,1,2],
                "to":[16,15,14],
                "shade":false,
                "rotation":{"origin":[8,8,8],"axis":"y","angle":45,"rescale":true},
                "faces":{
                  "north":{"texture":"#side","uv":[0,1,16,15],"cullface":"north","rotation":90,"tintindex":0}
                }
              }]
            }
            """
        ));

        assertEquals("block/cube_all", model.parentId);
        assertFalse(model.ambientOcclusion);
        assertEquals("block/stone", model.textures.get("all"));
        assertEquals(1, model.elements.size());

        BlockModel.Element element = model.elements.getFirst();
        assertArrayEquals(new float[]{0, 1, 2}, element.from, 0.0001f);
        assertArrayEquals(new float[]{16, 15, 14}, element.to, 0.0001f);
        assertFalse(element.shade);
        assertEquals("y", element.rotation.axis);
        assertEquals(45.0f, element.rotation.angle, 0.0001f);
        assertTrue(element.rotation.rescale);

        BlockModel.Face face = element.faces.get("north");
        assertEquals("block/stone", face.resolveTexture(model.textures));
        assertEquals("north", face.cullface);
        assertEquals(90, face.rotation);
        assertEquals(0, face.tintindex);
        assertArrayEquals(new float[]{0, 1, 16, 15}, face.uv, 0.0001f);
    }

    private static ByteArrayInputStream json(String source) {
        return new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8));
    }
}
