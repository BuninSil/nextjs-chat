package main

import (
	"runtime"
	"sync"

	"github.com/energye/systray"
)

// Значок в трее: зелёная молния — подключено, серая — нет. Клик — открыть окно,
// меню — подключить/отключить, сменить сервер, выход.

var (
	trayMu    sync.Mutex
	trayReady bool
	trayApp   *App
	mToggle   *systray.MenuItem
	mServer   *systray.MenuItem
)

func startTray(a *App) {
	// Окно значка и его цикл сообщений Windows должны жить в одном системном потоке
	runtime.LockOSThread()
	trayApp = a
	systray.Run(func() {
		systray.SetIcon(trayIcon(false))
		systray.SetTitle("Fast VPN")
		systray.SetTooltip("Fast VPN — отключено")
		systray.SetOnClick(func(menu systray.IMenu) { a.ShowWindow() })
		systray.SetOnDClick(func(menu systray.IMenu) { a.ShowWindow() })
		mOpen := systray.AddMenuItem("Открыть Fast VPN", "")
		mOpen.Click(func() { a.ShowWindow() })
		systray.AddSeparator()
		mToggle = systray.AddMenuItem("Подключить", "")
		mToggle.Click(func() { logf("TILE", "трей: подключить/отключить"); toggle() })
		mServer = systray.AddMenuItem("Сменить сервер", "")
		mServer.Click(func() {
			logf("TILE", "трей: сменить сервер")
			go func() { toast(a.SwitchNext()) }()
		})
		systray.AddSeparator()
		mQuit := systray.AddMenuItem("Выход", "")
		mQuit.Click(func() { a.Quit() })
		trayMu.Lock()
		trayReady = true
		trayMu.Unlock()
		trayRefresh()
	}, func() {})
}

// trayRefresh — значок, подсказка и пункты меню по текущему состоянию.
func trayRefresh() {
	trayMu.Lock()
	defer trayMu.Unlock()
	if !trayReady {
		return
	}
	on := core.Running()
	systray.SetIcon(trayIcon(on))
	switch {
	case on:
		systray.SetTooltip("Fast VPN — " + orDefault(nameOf(getSettings().SelectedTag), "подключено"))
		mToggle.SetTitle("Отключить")
		mServer.Enable()
	case core.Starting() || busy.Load():
		systray.SetTooltip("Fast VPN — подключаюсь…")
		mToggle.SetTitle("Отключить")
		mServer.Disable()
	default:
		systray.SetTooltip("Fast VPN — отключено")
		mToggle.SetTitle("Подключить")
		mServer.Disable()
	}
}

func trayNotify(msg string) {
	trayMu.Lock()
	defer trayMu.Unlock()
	if trayReady {
		systray.SetTooltip(msg)
	}
}

func quitTray() {
	trayMu.Lock()
	ready := trayReady
	trayReady = false
	trayMu.Unlock()
	if ready {
		systray.Quit()
	}
}
