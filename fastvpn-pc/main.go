package main

import (
	"io/fs"
	"os"

	"github.com/wailsapp/wails/v2"
	"github.com/wailsapp/wails/v2/pkg/options"
	"github.com/wailsapp/wails/v2/pkg/options/assetserver"
	"github.com/wailsapp/wails/v2/pkg/options/windows"
)

func main() {
	loadSettings()
	loadSubscription()
	app := NewApp()
	// --tray: запуск вместе с Windows — сразу в трей, без окна
	hidden := len(os.Args) > 1 && os.Args[1] == "--tray"
	assets, _ := fs.Sub(frontendFS, "frontend/dist")
	err := wails.Run(&options.App{
		Title:             "Fast VPN",
		Width:             440,
		Height:            800,
		MinWidth:          380,
		MinHeight:         620,
		StartHidden:       hidden,
		BackgroundColour:  &options.RGBA{R: 18, G: 20, B: 23, A: 255},
		AssetServer:       &assetserver.Options{Assets: assets},
		OnStartup:         app.startup,
		OnBeforeClose:     app.beforeClose,
		Bind:              []interface{}{app},
		SingleInstanceLock: &options.SingleInstanceLock{
			UniqueId:               "fast-vpn-buninsil",
			OnSecondInstanceLaunch: func(options.SecondInstanceData) { app.ShowWindow() },
		},
		Windows: &windows.Options{
			Theme: windows.Dark,
		},
	})
	if err != nil {
		logf("ERR", "окно не запустилось: %v", err)
	}
	disconnect()
}
