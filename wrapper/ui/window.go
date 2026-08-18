package ui

import (
	"bytes"
	"encoding/base64"
	"fmt"
	"image"
	"image/color"
	"os"
	"path/filepath"
	"strings"
	"sync"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/app"
	"fyne.io/fyne/v2/canvas"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/dialog"
	"fyne.io/fyne/v2/layout"
	"fyne.io/fyne/v2/theme"
	"fyne.io/fyne/v2/widget"

	"github.com/kastrick/minesport/ipc"
	"github.com/kastrick/minesport/launcher"
	_ "image/png"
)

// NOTE: This file intentionally remains the current UI implementation from the
// active redesign. The source is kept here so the application can build from
// main while the reusable helper widgets/renderers are merged separately.
