package main

import (
	"embed"
	"io/fs"
)

// Ядра VPN (sing-box и Xray для Windows) кладёт сборка в bin/ перед компиляцией.
//
//go:embed all:bin
var binFS embed.FS

//go:embed assets/rules/*.srs
var rulesFS embed.FS

//go:embed all:frontend/dist
var frontendFS embed.FS

//go:embed build/tray-on.ico build/tray-off.ico
var trayFS embed.FS

// appVersion подставляется при сборке: -ldflags "-X main.appVersion=1.0.N"
var appVersion = "1.0.0"

func embeddedBins() map[string][]byte {
	out := map[string][]byte{}
	for _, name := range []string{coreExe, xrayExe} {
		if b, err := binFS.ReadFile("bin/" + name); err == nil {
			out[name] = b
		}
	}
	return out
}

func embeddedRules() map[string][]byte {
	out := map[string][]byte{}
	entries, _ := fs.ReadDir(rulesFS, "assets/rules")
	for _, e := range entries {
		if b, err := rulesFS.ReadFile("assets/rules/" + e.Name()); err == nil {
			out[e.Name()] = b
		}
	}
	return out
}

func trayIcon(on bool) []byte {
	name := "build/tray-off.ico"
	if on {
		name = "build/tray-on.ico"
	}
	b, _ := trayFS.ReadFile(name)
	return b
}
