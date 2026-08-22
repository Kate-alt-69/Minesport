package main

import (
	"log"
	"os"
	"strings"

	"github.com/kastrick/minesport/bridgecapture"
)

var desktopBridgeCapture *bridgecapture.Server

// The Fabric bridge connects to Minesport as soon as its Minecraft client is
// ready. Start the receiver with the normal desktop app rather than waiting for
// an export click, so a runtime registry dump can be cached before export.
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
