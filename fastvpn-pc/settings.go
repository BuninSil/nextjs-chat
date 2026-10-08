package main

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"sync"
)

// Settings — все настройки приложения, хранятся в %APPDATA%\FastVPN\settings.json.
type Settings struct {
	SubURL      string `json:"subUrl"`
	SelectedTag string `json:"selectedTag"`
	AutoSelect  bool   `json:"autoSelect"`
	RuDirect    bool   `json:"ruDirect"`
	AdBlock     bool   `json:"adBlock"`
	Theme       string `json:"theme"`
	Anim        string `json:"anim"`
	// Запуск вместе с Windows (через планировщик, без окна UAC)
	Autostart bool `json:"autostart"`
	// Подключаться сразу при запуске приложения
	ConnectOnStart bool `json:"connectOnStart"`
	// Крестик сворачивает в трей, а не закрывает
	CloseToTray bool   `json:"closeToTray"`
	AutoUpdate  bool   `json:"autoUpdate"`
	Favorites   []string `json:"favorites"`
	Hidden      []string `json:"hidden"`
	HWID        string   `json:"hwid"`
	SubAt       int64    `json:"subAt"`
	RankAt      int64    `json:"rankAt"`
	// Страна выхода в интернет по серверам (tag -> код страны)
	Exits map[string]string `json:"exits"`
	// Версия, для которой уже показали «что нового»
	SeenVersion string `json:"seenVersion"`
}

var (
	settingsMu sync.Mutex
	cfg        = defaultSettings()
)

func defaultSettings() *Settings {
	return &Settings{
		AutoSelect:  true,
		RuDirect:    true,
		Theme:       "dark",
		Anim:        "warp",
		CloseToTray: true,
		AutoUpdate:  true,
		Exits:       map[string]string{},
	}
}

// dataDir — папка данных приложения.
func dataDir() string {
	base, err := os.UserConfigDir()
	if err != nil {
		base = "."
	}
	d := filepath.Join(base, "FastVPN")
	_ = os.MkdirAll(d, 0o755)
	return d
}

func dataFile(name string) string { return filepath.Join(dataDir(), name) }

func loadSettings() {
	settingsMu.Lock()
	defer settingsMu.Unlock()
	s := defaultSettings()
	if b, err := os.ReadFile(dataFile("settings.json")); err == nil {
		_ = json.Unmarshal(b, s)
	}
	if s.Exits == nil {
		s.Exits = map[string]string{}
	}
	if s.HWID == "" {
		b := make([]byte, 8)
		_, _ = rand.Read(b)
		s.HWID = hex.EncodeToString(b)
	}
	cfg = s
}

func saveSettings() {
	settingsMu.Lock()
	b, _ := json.MarshalIndent(cfg, "", "  ")
	settingsMu.Unlock()
	tmp := dataFile("settings.json.tmp")
	if os.WriteFile(tmp, b, 0o644) == nil {
		_ = os.Rename(tmp, dataFile("settings.json"))
	}
}

// withSettings — изменить настройки под замком и сохранить.
func withSettings(f func(s *Settings)) {
	settingsMu.Lock()
	f(cfg)
	settingsMu.Unlock()
	saveSettings()
}

func getSettings() Settings {
	settingsMu.Lock()
	defer settingsMu.Unlock()
	c := *cfg
	c.Exits = make(map[string]string, len(cfg.Exits))
	for k, v := range cfg.Exits {
		c.Exits[k] = v
	}
	c.Favorites = append([]string(nil), cfg.Favorites...)
	c.Hidden = append([]string(nil), cfg.Hidden...)
	return c
}

func contains(list []string, s string) bool {
	for _, x := range list {
		if x == s {
			return true
		}
	}
	return false
}

func without(list []string, s string) []string {
	out := list[:0:0]
	for _, x := range list {
		if x != s {
			out = append(out, x)
		}
	}
	return out
}
