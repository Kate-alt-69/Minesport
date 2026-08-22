package ui

// Fyne 2.5 does not expose fyne.Do yet, but the desktop window implementation
// promotes QueueEvent from its common window. Use that tiny public method shape
// instead of importing Fyne internals so background IPC/worker goroutines never
// mutate widgets directly.
type uiEventQueuer interface {
	QueueEvent(func())
}

// dispatchUI schedules fn on the window's ordered event queue when available.
// The fallback keeps headless/test windows working. A closed production window
// may reject a late event while the app is shutting down; dropping that event is
// safer than touching destroyed widgets from the worker goroutine.
func (ms *MinesportApp) dispatchUI(fn func()) {
	if fn == nil {
		return
	}
	if ms == nil || ms.window == nil {
		fn()
		return
	}
	if queue, ok := ms.window.(uiEventQueuer); ok {
		queued := false
		func() {
			defer func() { _ = recover() }()
			queue.QueueEvent(fn)
			queued = true
		}()
		if queued {
			return
		}
		return
	}
	fn()
}

func (ms *MinesportApp) appendLogAsync(message string) {
	ms.dispatchUI(func() { ms.appendLog(message) })
}
