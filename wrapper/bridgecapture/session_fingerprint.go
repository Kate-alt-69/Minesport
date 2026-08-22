package bridgecapture

import (
	"fmt"
	"path/filepath"
	"strings"
)

// BeginSessionWithFingerprint records a capture session using a fingerprint that
// was already computed by the caller. Runtime-cache jobs hash the selected mod
// set exactly once in their background goroutine, then reuse that identity for
// readiness polling instead of repeatedly reading every JAR.
func BeginSessionWithFingerprint(version, modsPath, fingerprint string) error {
	version = strings.TrimSpace(version)
	modsPath = filepath.Clean(strings.TrimSpace(modsPath))
	fingerprint = strings.TrimSpace(fingerprint)
	if version == "" {
		return fmt.Errorf("minecraft version is required")
	}
	if modsPath == "" || modsPath == "." {
		return fmt.Errorf("mods folder path is required")
	}
	if fingerprint == "" {
		return fmt.Errorf("mods fingerprint is required")
	}
	captureSessions.Lock()
	captureSessions.byVersion[version] = captureSession{
		Version:         version,
		ModsPath:        modsPath,
		ModsFingerprint: fingerprint,
	}
	captureSessions.Unlock()
	return nil
}
