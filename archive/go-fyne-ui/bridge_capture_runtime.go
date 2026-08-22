package main

import (
	"log"
	"os"
	"strings"

	"github.com/kastrick/minesport/bridgecapture"
)

var desktopBridgeCapture *bridgecapture.Server

// Archived with the Fyne desktop. The active Rust/Slint desktop owns runtime
// registry capture through desktop/src/registry.rs and runtime_worker.rs.
func init() {
	if len(os.Args) > 1 {
		arg := strings.TrimSpace(os.Args[1])
		if strings.HasPrefix(arg, "-test.") || strings.HasPrefix(arg, "--") || arg == "-h" {
			return
		}
	}

	server, err := bridgecapture.Start(func(message string) {
		log.Printf("[bridge] %s", message)
	})
	if err != nil {
		log.Printf("[bridge] runtime capture unavailable: %v", err)
		return
	}
	desktopBridgeCapture = server
}

func shutdownDesktopBridgeCapture() {
	if desktopBridgeCapture != nil {
		_ = desktopBridgeCapture.Close()
		desktopBridgeCapture = nil
		return
	}
	bridgecapture.CleanupStaged()
}
