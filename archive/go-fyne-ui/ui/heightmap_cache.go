package ui

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/kastrick/minesport/appdirs"
	"github.com/kastrick/minesport/ipc"
)

type cachedHeightmap struct {
	Fingerprint string `json:"fingerprint"`
	MinX        int    `json:"minX"`
	MinZ        int    `json:"minZ"`
	MaxX        int    `json:"maxX"`
	MaxZ        int    `json:"maxZ"`
	Scale       int    `json:"scale"`
	PNGFile     string `json:"pngFile"`
}

func heightmapFingerprint(worldPath string) (string, error) {
	hash := sha256.New()
	absolute, err := filepath.Abs(worldPath)
	if err != nil {
		return "", err
	}
	fmt.Fprintln(hash, filepath.Clean(absolute))

	_, regionDir, err := ipc.ResolveOverworldRegion(worldPath)
	if err != nil {
		return "", err
	}

	paths := []string{filepath.Join(worldPath, "level.dat")}
	entries, err := os.ReadDir(regionDir)
	if err != nil {
		return "", err
	}
	for _, entry := range entries {
		if entry.IsDir() {
			continue
		}
		ext := strings.ToLower(filepath.Ext(entry.Name()))
		if ext == ".mca" || ext == ".mcr" {
			paths = append(paths, filepath.Join(regionDir, entry.Name()))
		}
	}
	for _, path := range paths {
		info, err := os.Stat(path)
		if err != nil {
			return "", err
		}
		relative, relErr := filepath.Rel(worldPath, path)
		if relErr != nil {
			relative = path
		}
		fmt.Fprintf(hash, "%s|%d|%d\n", filepath.Clean(relative), info.Size(), info.ModTime().UnixNano())
	}
	return hex.EncodeToString(hash.Sum(nil)), nil
}

func heightmapCachePaths(worldPath string) (metadataPath, pngPath string, err error) {
	absolute, err := filepath.Abs(worldPath)
	if err != nil {
		return "", "", err
	}
	key := sha256.Sum256([]byte(filepath.Clean(absolute)))
	name := hex.EncodeToString(key[:16])
	dir := filepath.Join(appdirs.CacheRoot(), "heightmaps")
	return filepath.Join(dir, name+".json"), filepath.Join(dir, name+".png"), nil
}

func loadCachedHeightmap(worldPath string) (*cachedHeightmap, []byte, bool) {
	fingerprint, err := heightmapFingerprint(worldPath)
	if err != nil {
		return nil, nil, false
	}
	metadataPath, pngPath, err := heightmapCachePaths(worldPath)
	if err != nil {
		return nil, nil, false
	}
	metadataBytes, err := os.ReadFile(metadataPath)
	if err != nil {
		return nil, nil, false
	}
	var metadata cachedHeightmap
	if json.Unmarshal(metadataBytes, &metadata) != nil || metadata.Fingerprint != fingerprint {
		return nil, nil, false
	}
	pngBytes, err := os.ReadFile(pngPath)
	if err != nil || len(pngBytes) == 0 {
		return nil, nil, false
	}
	return &metadata, pngBytes, true
}

func saveCachedHeightmap(worldPath string, pngBytes []byte, minX, minZ, maxX, maxZ, scale int) error {
	fingerprint, err := heightmapFingerprint(worldPath)
	if err != nil {
		return err
	}
	metadataPath, pngPath, err := heightmapCachePaths(worldPath)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(metadataPath), 0o755); err != nil {
		return err
	}
	metadata := cachedHeightmap{
		Fingerprint: fingerprint,
		MinX:        minX, MinZ: minZ, MaxX: maxX, MaxZ: maxZ, Scale: scale,
		PNGFile: filepath.Base(pngPath),
	}
	metadataBytes, err := json.Marshal(metadata)
	if err != nil {
		return err
	}
	if err := os.WriteFile(pngPath+".tmp", pngBytes, 0o644); err != nil {
		return err
	}
	// Windows cannot rename over an existing destination. The metadata is
	// written last, so an interrupted refresh can only cause a cache miss.
	_ = os.Remove(pngPath)
	if err := os.Rename(pngPath+".tmp", pngPath); err != nil {
		return err
	}
	if err := os.WriteFile(metadataPath+".tmp", metadataBytes, 0o644); err != nil {
		return err
	}
	_ = os.Remove(metadataPath)
	return os.Rename(metadataPath+".tmp", metadataPath)
}
