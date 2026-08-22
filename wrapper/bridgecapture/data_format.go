package bridgecapture

import (
	"bufio"
	"encoding/binary"
	"fmt"
	"io"
	"math"
	"os"
	"sort"
)

const (
	registryDataMagic      = "MSREGD01"
	maxRegistryStringBytes = 4 << 20
	maxRegistryBlocks      = 1_000_000
	maxRegistryVariants    = 1_000_000
	maxRegistryQuads       = 20_000_000
	maxRegistryProperties  = 4096
	maxRegistryLoadedMods  = 100_000
)

// writeSnapshotData stores runtime geometry in a compact deterministic binary
// representation. The Bridge wire protocol remains newline JSON for easy
// compatibility debugging; this format is only the persistent hot-path cache.
func writeSnapshotData(writer io.Writer, snapshot Snapshot) error {
	if snapshot.Schema != SnapshotSchema {
		return fmt.Errorf("unsupported runtime registry schema %d; expected %d", snapshot.Schema, SnapshotSchema)
	}
	w := bufio.NewWriterSize(writer, 256*1024)
	if _, err := w.WriteString(registryDataMagic); err != nil {
		return err
	}
	if err := binary.Write(w, binary.BigEndian, int32(snapshot.Schema)); err != nil {
		return err
	}
	for _, value := range []string{
		snapshot.MinecraftVersion,
		snapshot.LoaderVersion,
		snapshot.ModsFingerprint,
		snapshot.CapturedAt,
	} {
		if err := writeDataString(w, value); err != nil {
			return err
		}
	}
	if err := writeCount(w, len(snapshot.LoadedMods), maxRegistryLoadedMods, "loaded mods"); err != nil {
		return err
	}
	for _, mod := range snapshot.LoadedMods {
		if err := writeDataString(w, mod); err != nil {
			return err
		}
	}

	blockIDs := make([]string, 0, len(snapshot.Blocks))
	for blockID := range snapshot.Blocks {
		blockIDs = append(blockIDs, blockID)
	}
	sort.Strings(blockIDs)
	if err := writeCount(w, len(blockIDs), maxRegistryBlocks, "blocks"); err != nil {
		return err
	}
	for _, blockID := range blockIDs {
		block := snapshot.Blocks[blockID]
		if err := writeDataString(w, blockID); err != nil {
			return err
		}
		if err := writeDataString(w, block.VanillaMapping); err != nil {
			return err
		}
		if err := writeDataString(w, block.LoaderType); err != nil {
			return err
		}
		if err := writeCount(w, len(block.Variants), maxRegistryVariants, "variants"); err != nil {
			return err
		}
		for _, variant := range block.Variants {
			if err := writeStringMap(w, variant.Properties); err != nil {
				return err
			}
			if err := writeCount(w, len(variant.Quads), maxRegistryQuads, "quads"); err != nil {
				return err
			}
			for _, quad := range variant.Quads {
				if len(quad.Vertices) != 32 {
					return fmt.Errorf("runtime quad for %s has %d floats, want 32", blockID, len(quad.Vertices))
				}
				for _, value := range quad.Vertices {
					if math.IsNaN(float64(value)) || math.IsInf(float64(value), 0) {
						return fmt.Errorf("runtime quad for %s contains non-finite vertex data", blockID)
					}
					if err := binary.Write(w, binary.BigEndian, value); err != nil {
						return err
					}
				}
				if err := writeDataString(w, quad.TextureID); err != nil {
					return err
				}
				if err := binary.Write(w, binary.BigEndian, int32(quad.Face)); err != nil {
					return err
				}
				shade := byte(0)
				if quad.Shade {
					shade = 1
				}
				if err := w.WriteByte(shade); err != nil {
					return err
				}
				if err := binary.Write(w, binary.BigEndian, int32(quad.TintIndex)); err != nil {
					return err
				}
			}
		}

		if err := writeCount(w, len(block.Lights), maxRegistryVariants, "light states"); err != nil {
			return err
		}
		for _, light := range block.Lights {
			if light.LightLevel < 0 || light.LightLevel > 15 {
				return fmt.Errorf("runtime light level %d for %s is outside 0..15", light.LightLevel, blockID)
			}
			if err := writeStringMap(w, light.Properties); err != nil {
				return err
			}
			if err := binary.Write(w, binary.BigEndian, int32(light.LightLevel)); err != nil {
				return err
			}
		}
	}
	return w.Flush()
}

func readSnapshotData(reader io.Reader) (Snapshot, error) {
	r := bufio.NewReaderSize(reader, 256*1024)
	magic := make([]byte, len(registryDataMagic))
	if _, err := io.ReadFull(r, magic); err != nil {
		return Snapshot{}, err
	}
	if string(magic) != registryDataMagic {
		return Snapshot{}, fmt.Errorf("invalid runtime registry magic %q", string(magic))
	}
	var schema int32
	if err := binary.Read(r, binary.BigEndian, &schema); err != nil {
		return Snapshot{}, err
	}
	if int(schema) != SnapshotSchema {
		return Snapshot{}, fmt.Errorf("unsupported runtime registry schema %d; expected %d", schema, SnapshotSchema)
	}
	minecraftVersion, err := readDataString(r)
	if err != nil {
		return Snapshot{}, err
	}
	loaderVersion, err := readDataString(r)
	if err != nil {
		return Snapshot{}, err
	}
	fingerprint, err := readDataString(r)
	if err != nil {
		return Snapshot{}, err
	}
	capturedAt, err := readDataString(r)
	if err != nil {
		return Snapshot{}, err
	}
	modCount, err := readCount(r, maxRegistryLoadedMods, "loaded mods")
	if err != nil {
		return Snapshot{}, err
	}
	loadedMods := make([]string, modCount)
	for index := range loadedMods {
		loadedMods[index], err = readDataString(r)
		if err != nil {
			return Snapshot{}, err
		}
	}

	blockCount, err := readCount(r, maxRegistryBlocks, "blocks")
	if err != nil {
		return Snapshot{}, err
	}
	blocks := make(map[string]RuntimeBlock, blockCount)
	for blockIndex := 0; blockIndex < blockCount; blockIndex++ {
		blockID, err := readDataString(r)
		if err != nil {
			return Snapshot{}, err
		}
		vanillaMapping, err := readDataString(r)
		if err != nil {
			return Snapshot{}, err
		}
		loaderType, err := readDataString(r)
		if err != nil {
			return Snapshot{}, err
		}
		variantCount, err := readCount(r, maxRegistryVariants, "variants")
		if err != nil {
			return Snapshot{}, err
		}
		variants := make([]BlockVariant, variantCount)
		for variantIndex := range variants {
			properties, err := readStringMap(r)
			if err != nil {
				return Snapshot{}, err
			}
			quadCount, err := readCount(r, maxRegistryQuads, "quads")
			if err != nil {
				return Snapshot{}, err
			}
			quads := make([]BakedQuad, quadCount)
			for quadIndex := range quads {
				vertices := make([]float32, 32)
				for vertexIndex := range vertices {
					if err := binary.Read(r, binary.BigEndian, &vertices[vertexIndex]); err != nil {
						return Snapshot{}, err
					}
					value := vertices[vertexIndex]
					if math.IsNaN(float64(value)) || math.IsInf(float64(value), 0) {
						return Snapshot{}, fmt.Errorf("runtime registry contains non-finite vertex data")
					}
				}
				textureID, err := readDataString(r)
				if err != nil {
					return Snapshot{}, err
				}
				var face int32
				if err := binary.Read(r, binary.BigEndian, &face); err != nil {
					return Snapshot{}, err
				}
				shade, err := r.ReadByte()
				if err != nil {
					return Snapshot{}, err
				}
				if shade > 1 {
					return Snapshot{}, fmt.Errorf("invalid runtime quad shade byte %d", shade)
				}
				var tintIndex int32
				if err := binary.Read(r, binary.BigEndian, &tintIndex); err != nil {
					return Snapshot{}, err
				}
				quads[quadIndex] = BakedQuad{
					Vertices:  vertices,
					TextureID: textureID,
					Face:      int(face),
					Shade:     shade == 1,
					TintIndex: int(tintIndex),
				}
			}
			variants[variantIndex] = BlockVariant{Properties: properties, Quads: quads}
		}

		lightCount, err := readCount(r, maxRegistryVariants, "light states")
		if err != nil {
			return Snapshot{}, err
		}
		lights := make([]LightState, lightCount)
		for lightIndex := range lights {
			properties, err := readStringMap(r)
			if err != nil {
				return Snapshot{}, err
			}
			var level int32
			if err := binary.Read(r, binary.BigEndian, &level); err != nil {
				return Snapshot{}, err
			}
			if level < 0 || level > 15 {
				return Snapshot{}, fmt.Errorf("runtime registry contains invalid light level %d", level)
			}
			lights[lightIndex] = LightState{Properties: properties, LightLevel: int(level)}
		}
		blocks[blockID] = RuntimeBlock{
			VanillaMapping: vanillaMapping,
			LoaderType:     loaderType,
			Variants:       variants,
			Lights:         lights,
		}
	}

	return Snapshot{
		Schema:           int(schema),
		MinecraftVersion: minecraftVersion,
		LoaderVersion:    loaderVersion,
		LoadedMods:       loadedMods,
		ModsFingerprint:  fingerprint,
		Blocks:           blocks,
		CapturedAt:       capturedAt,
	}, nil
}

func readSnapshotFile(path string) (Snapshot, error) {
	file, err := os.Open(path)
	if err != nil {
		return Snapshot{}, err
	}
	defer file.Close()
	return readSnapshotData(file)
}

func writeCount(w io.Writer, count, maximum int, label string) error {
	if count < 0 || count > maximum {
		return fmt.Errorf("runtime registry %s count %d exceeds limit %d", label, count, maximum)
	}
	return binary.Write(w, binary.BigEndian, int32(count))
}

func readCount(r io.Reader, maximum int, label string) (int, error) {
	var count int32
	if err := binary.Read(r, binary.BigEndian, &count); err != nil {
		return 0, err
	}
	if count < 0 || int(count) > maximum {
		return 0, fmt.Errorf("runtime registry %s count %d exceeds limit %d", label, count, maximum)
	}
	return int(count), nil
}

func writeDataString(w io.Writer, value string) error {
	bytes := []byte(value)
	if len(bytes) > maxRegistryStringBytes {
		return fmt.Errorf("runtime registry string is too large: %d bytes", len(bytes))
	}
	if err := binary.Write(w, binary.BigEndian, int32(len(bytes))); err != nil {
		return err
	}
	_, err := w.Write(bytes)
	return err
}

func readDataString(r io.Reader) (string, error) {
	length, err := readCount(r, maxRegistryStringBytes, "string bytes")
	if err != nil {
		return "", err
	}
	if length == 0 {
		return "", nil
	}
	bytes := make([]byte, length)
	if _, err := io.ReadFull(r, bytes); err != nil {
		return "", err
	}
	return string(bytes), nil
}

func writeStringMap(w io.Writer, values map[string]string) error {
	keys := make([]string, 0, len(values))
	for key := range values {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	if err := writeCount(w, len(keys), maxRegistryProperties, "properties"); err != nil {
		return err
	}
	for _, key := range keys {
		if err := writeDataString(w, key); err != nil {
			return err
		}
		if err := writeDataString(w, values[key]); err != nil {
			return err
		}
	}
	return nil
}

func readStringMap(r io.Reader) (map[string]string, error) {
	count, err := readCount(r, maxRegistryProperties, "properties")
	if err != nil {
		return nil, err
	}
	values := make(map[string]string, count)
	for index := 0; index < count; index++ {
		key, err := readDataString(r)
		if err != nil {
			return nil, err
		}
		value, err := readDataString(r)
		if err != nil {
			return nil, err
		}
		values[key] = value
	}
	return values, nil
}
