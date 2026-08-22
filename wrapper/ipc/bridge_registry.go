package ipc

import (
	"strings"

	"github.com/kastrick/minesport/bridgecapture"
)

// attachBridgeRegistry adds runtime bridge metadata only when it was captured
// from the same Minecraft version and current mod JAR set. A missing/stale
// registry is deliberately silent: the Java engine falls back to its native
// vanilla/mod heuristics instead of consuming untrusted old runtime metadata.
func attachBridgeRegistry(p *ExportParams, version, loader string) bool {
	if p == nil || strings.ToLower(strings.TrimSpace(loader)) != "fabric" {
		return false
	}
	version = strings.TrimSpace(version)
	modsPath := strings.TrimSpace(p.ModsPath)
	if version == "" || modsPath == "" {
		return false
	}
	path, ok := bridgecapture.SnapshotPathForMods(version, modsPath)
	if !ok {
		delete(p.Options, "bridgeRegistry")
		return false
	}
	if p.Options == nil {
		p.Options = map[string]string{}
	}
	p.Options["bridgeRegistry"] = path
	return true
}
