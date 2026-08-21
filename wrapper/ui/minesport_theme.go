package ui

import (
	"image/color"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/theme"
)

// minesportTheme keeps Fyne's typography/icons/metrics while replacing the
// generic desktop palette with a restrained deepslate + grass workbench.
type minesportTheme struct {
	base fyne.Theme
}

func newMinesportTheme() fyne.Theme {
	return &minesportTheme{base: theme.DefaultTheme()}
}

func (m *minesportTheme) Color(name fyne.ThemeColorName, _ fyne.ThemeVariant) color.Color {
	switch name {
	case theme.ColorNameBackground:
		return color.NRGBA{R: 18, G: 20, B: 20, A: 255}
	case theme.ColorNameHeaderBackground:
		return color.NRGBA{R: 25, G: 27, B: 27, A: 255}
	case theme.ColorNameButton:
		return color.NRGBA{R: 45, G: 47, B: 43, A: 255}
	case theme.ColorNameDisabledButton:
		return color.NRGBA{R: 31, G: 33, B: 32, A: 255}
	case theme.ColorNameInputBackground:
		return color.NRGBA{R: 14, G: 16, B: 16, A: 255}
	case theme.ColorNameInputBorder:
		return color.NRGBA{R: 65, G: 69, B: 64, A: 255}
	case theme.ColorNameMenuBackground:
		return color.NRGBA{R: 28, G: 30, B: 29, A: 255}
	case theme.ColorNameOverlayBackground:
		return color.NRGBA{R: 21, G: 24, B: 23, A: 252}
	case theme.ColorNameSeparator:
		return color.NRGBA{R: 49, G: 52, B: 49, A: 255}
	case theme.ColorNamePrimary, theme.ColorNameHyperlink:
		return color.NRGBA{R: 91, G: 166, B: 68, A: 255}
	case theme.ColorNameSelection:
		return color.NRGBA{R: 78, G: 138, B: 58, A: 150}
	case theme.ColorNameFocus:
		return color.NRGBA{R: 116, G: 190, B: 88, A: 170}
	case theme.ColorNameSuccess:
		return color.NRGBA{R: 108, G: 194, B: 82, A: 255}
	case theme.ColorNameWarning:
		return color.NRGBA{R: 221, G: 169, B: 70, A: 255}
	case theme.ColorNameError:
		return color.NRGBA{R: 221, G: 82, B: 72, A: 255}
	case theme.ColorNameForeground:
		return color.NRGBA{R: 229, G: 232, B: 225, A: 255}
	case theme.ColorNameDisabled:
		return color.NRGBA{R: 127, G: 132, B: 125, A: 255}
	case theme.ColorNamePlaceHolder:
		return color.NRGBA{R: 145, G: 150, B: 142, A: 255}
	case theme.ColorNameForegroundOnPrimary,
		theme.ColorNameForegroundOnSuccess,
		theme.ColorNameForegroundOnWarning,
		theme.ColorNameForegroundOnError:
		return color.NRGBA{R: 16, G: 19, B: 15, A: 255}
	default:
		return m.base.Color(name, theme.VariantDark)
	}
}

func (m *minesportTheme) Font(style fyne.TextStyle) fyne.Resource {
	return m.base.Font(style)
}

func (m *minesportTheme) Icon(name fyne.ThemeIconName) fyne.Resource {
	return m.base.Icon(name)
}

func (m *minesportTheme) Size(name fyne.ThemeSizeName) float32 {
	return m.base.Size(name)
}
