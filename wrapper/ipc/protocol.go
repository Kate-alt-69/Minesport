package ipc

// ── Messages Go → Java ────────────────────────────────────────────────────────

// ExportRequest documents the shape of an export request. The struct that's
// actually marshalled and sent is ipc.Request in engine.go (which is kept
// in sync with the Java side's IpcMode.handleExport) — this type isn't
// constructed anywhere, it's here as a quick-reference alongside EngineMessage.
type ExportRequest struct {
	Command    string `json:"command"`    // "export"
	WorldPath  string `json:"worldPath"`
	MinX       int    `json:"minX"`
	MinY       int    `json:"minY"`
	MinZ       int    `json:"minZ"`
	MaxX       int    `json:"maxX"`
	MaxY       int    `json:"maxY"`
	MaxZ       int    `json:"maxZ"`
	Format     string `json:"format"`     // "OBJ" or "glTF"
	ExportMode string `json:"exportMode"` // "GROUPED_BY_TYPE", "ALL_MERGED", "INDIVIDUAL"
	OutputDir  string `json:"outputDir"`
}

// PingRequest checks if engine is alive.
type PingRequest struct {
	Command string `json:"command"` // "ping"
}

// ── Messages Java → Go ────────────────────────────────────────────────────────

// EngineMessage is any message from the Java engine.
type EngineMessage struct {
	Type    string `json:"type"`    // "progress", "log", "done", "error", "pong", "heightmap"
	Message string `json:"message"` // human readable
	Done    int    `json:"done"`    // for progress: blocks done
	Total   int    `json:"total"`   // for progress: total blocks
	Percent int    `json:"percent"` // 0-100
	Output  string `json:"output"`  // for "done": path to output file
	Error   string `json:"error"`   // for "error": error detail
	// Heightmap fields
	Image   string `json:"image"`   // base64 PNG
	MinX    int    `json:"minX"`
	MinZ    int    `json:"minZ"`
	MaxX    int    `json:"maxX"`
	MaxZ    int    `json:"maxZ"`
	Scale   int    `json:"scale"`
}
