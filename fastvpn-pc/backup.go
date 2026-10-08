package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// Перенос настроек: тот же формат файла, что в Android-версии (fast-vpn-backup), — можно перенести
// подписку и избранные серверы с телефона на компьютер и обратно.

type backupEntry struct {
	T string          `json:"t"`
	V json.RawMessage `json:"v"`
}

func exportBackup(path string) error {
	s := getSettings()
	prefs := map[string]any{}
	put := func(k, t string, v any) { prefs[k] = map[string]any{"t": t, "v": v} }
	put("subUrl", "s", s.SubURL)
	put("selectedTag", "s", s.SelectedTag)
	put("autoSelect", "b", s.AutoSelect)
	put("ruDirect", "b", s.RuDirect)
	put("adBlock", "b", s.AdBlock)
	put("theme", "s", s.Theme)
	put("anim", "s", s.Anim)
	put("favorites", "set", orEmpty(s.Favorites))
	put("hidden", "set", orEmpty(s.Hidden))
	if b, err := json.Marshal(s.Exits); err == nil {
		put("exits_v2", "s", string(b))
	}
	o := map[string]any{
		"format":  "fast-vpn-backup",
		"version": 1,
		"app":     "PC " + appVersion,
		"created": time.Now().Format("2006-01-02 15:04"),
		"prefs":   prefs,
	}
	if b, err := os.ReadFile(dataFile("subscription.txt")); err == nil {
		o["subscription"] = string(b)
	}
	if b, err := os.ReadFile(dataFile("subscription-info.txt")); err == nil {
		o["subInfo"] = string(b)
	}
	b, _ := json.MarshalIndent(o, "", " ")
	logf("UI", "настройки сохранены в файл")
	return os.WriteFile(path, b, 0o644)
}

func orEmpty(l []string) []string {
	if l == nil {
		return []string{}
	}
	return l
}

type BackupInfo struct {
	Created string `json:"created"`
	App     string `json:"app"`
	Servers int    `json:"servers"`
}

func readBackup(path string) (map[string]any, BackupInfo, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, BackupInfo{}, err
	}
	var o map[string]any
	if json.Unmarshal(b, &o) != nil || o["format"] != "fast-vpn-backup" {
		return nil, BackupInfo{}, errors.New("Это не файл настроек Fast VPN")
	}
	info := BackupInfo{}
	info.Created, _ = o["created"].(string)
	info.App, _ = o["app"].(string)
	if sub, _ := o["subscription"].(string); sub != "" {
		info.Servers = len(parseAll(sub))
	}
	return o, info, nil
}

func importBackup(path string) error {
	o, _, err := readBackup(path)
	if err != nil {
		return err
	}
	disconnect()
	raw, _ := json.Marshal(o["prefs"])
	var prefs map[string]backupEntry
	_ = json.Unmarshal(raw, &prefs)
	str := func(k string) (string, bool) {
		e, ok := prefs[k]
		if !ok {
			return "", false
		}
		var v string
		return v, json.Unmarshal(e.V, &v) == nil
	}
	boolean := func(k string) (bool, bool) {
		e, ok := prefs[k]
		if !ok {
			return false, false
		}
		var v bool
		return v, json.Unmarshal(e.V, &v) == nil
	}
	set := func(k string) []string {
		var v []string
		if e, ok := prefs[k]; ok {
			_ = json.Unmarshal(e.V, &v)
		}
		return v
	}
	withSettings(func(s *Settings) {
		if v, ok := str("subUrl"); ok {
			s.SubURL = v
		}
		if v, ok := str("selectedTag"); ok {
			s.SelectedTag = v
		}
		if v, ok := boolean("autoSelect"); ok {
			s.AutoSelect = v
		}
		if v, ok := boolean("ruDirect"); ok {
			s.RuDirect = v
		}
		if v, ok := boolean("adBlock"); ok {
			s.AdBlock = v
		}
		if v, ok := str("theme"); ok && v != "" {
			s.Theme = v
		}
		if v, ok := str("anim"); ok && v != "" {
			s.Anim = v
		}
		s.Favorites = set("favorites")
		s.Hidden = set("hidden")
		s.Exits = map[string]string{}
		if v, ok := str("exits_v2"); ok {
			_ = json.Unmarshal([]byte(v), &s.Exits)
		}
		s.RankAt = 0
	})
	if sub, _ := o["subscription"].(string); sub != "" {
		_ = os.WriteFile(dataFile("subscription.txt"), []byte(sub), 0o644)
	} else {
		_ = os.Remove(dataFile("subscription.txt"))
	}
	if info, _ := o["subInfo"].(string); info != "" {
		_ = os.WriteFile(dataFile("subscription-info.txt"), []byte(info), 0o644)
	} else {
		_ = os.Remove(dataFile("subscription-info.txt"))
	}
	subMu.Lock()
	nodes, subInfo = nil, nil
	subMu.Unlock()
	loadSubscription()
	clearResults()
	logf("UI", "настройки загружены из файла")
	return nil
}

// ------------------------------ отчёт о проблеме ------------------------------

// buildReport — отчёт для разработчика: версия, система, настройки, замеры и полный журнал.
// Ссылки подписки, адресов серверов и паролей в нём нет.
func buildReport() string {
	s := getSettings()
	var b strings.Builder
	line := func(k string, v any) { fmt.Fprintf(&b, "%s: %v\n", k, v) }
	b.WriteString("=== Fast VPN для ПК — отчёт о проблеме ===\n")
	line("Время", time.Now().Format("2006-01-02 15:04:05"))
	line("Версия", appVersion)
	line("Windows", osVersion())
	b.WriteString("\n--- Настройки ---\n")
	line("Автовыбор", s.AutoSelect)
	line("Российские сайты напрямую", s.RuDirect)
	line("Блок рекламы", s.AdBlock)
	line("Тема", s.Theme)
	line("Анимация", s.Anim)
	line("Автозапуск", s.Autostart)
	b.WriteString("\n--- VPN ---\n")
	line("Подключён", core.Running())
	line("Последняя ошибка", orDefault(core.LastError(), "нет"))
	list := usableNodes(true)
	line("Серверов в подписке", len(list))
	line("Выбранный сервер", orDefault(nameOf(s.SelectedTag), "—"))
	b.WriteString("Замеры (лучшие 15):\n")
	for i, t := range rankedTags() {
		if i >= 15 {
			break
		}
		if n := nodeByTag(t); n != nil {
			r, _ := getResult(t)
			fmt.Fprintf(&b, "  %s · %s · %d ms ±%d", cleanName(*n), n.Type, r.Ms, r.Jitter)
			if c, ok := s.Exits[t]; ok {
				b.WriteString(" · выход " + c)
			}
			b.WriteString("\n")
		}
	}
	b.WriteString("\n--- Полный журнал действий ---\n")
	b.WriteString(readLog())
	return b.String()
}

// saveReport — отчёт в файл на рабочем столе, возвращает путь.
func saveReport() (string, error) {
	home, _ := os.UserHomeDir()
	dir := filepath.Join(home, "Desktop")
	if _, err := os.Stat(dir); err != nil {
		dir = home
	}
	p := filepath.Join(dir, "fast-vpn-report-"+time.Now().Format("20060102-1504")+".txt")
	return p, os.WriteFile(p, []byte(buildReport()), 0o644)
}
