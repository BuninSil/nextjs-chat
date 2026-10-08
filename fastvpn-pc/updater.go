package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Автообновление: релизы pc-v1.0.N на GitHub, установщик FastVPN-setup.exe ставится тихо и
// перезапускает приложение. Ход — в updState для окна.

const releasesAPI = "https://api.github.com/repos/BuninSil/nextjs-chat/releases?per_page=40"
const setupAsset = "FastVPN-setup.exe"

type UpdateInfo struct {
	Version string `json:"version"`
	Notes   string `json:"notes"`
	URL     string `json:"-"`
	Size    int64  `json:"size"`
}

type UpdState struct {
	Checking  bool        `json:"checking"`
	Available *UpdateInfo `json:"available"`
	Progress  int         `json:"progress"` // 0..100, -1 — не качаем
	Status    string      `json:"status"`
}

var (
	updMu sync.Mutex
	upd   = UpdState{Progress: -1}
)

func updState() UpdState { updMu.Lock(); defer updMu.Unlock(); return upd }

func updSet(f func(u *UpdState)) { updMu.Lock(); f(&upd); updMu.Unlock() }

func buildNum(v string) int {
	i := strings.LastIndex(v, ".")
	n, _ := strconv.Atoi(v[i+1:])
	return n
}

// cleanNotes — первая строка описания релиза (коротко, по строчке на версию).
func cleanNotes(body string) string {
	for _, l := range strings.Split(body, "\n") {
		l = strings.TrimSpace(strings.TrimPrefix(strings.TrimSpace(l), "- "))
		if l != "" {
			return strings.TrimSuffix(l, ".")
		}
	}
	return ""
}

// checkUpdate — есть ли новее. Новинки всех пропущенных версий — по строке на каждую.
func checkUpdate() (*UpdateInfo, error) {
	updSet(func(u *UpdState) { u.Checking = true; u.Status = "Проверяю обновления…" })
	defer updSet(func(u *UpdState) { u.Checking = false })
	cl := httpClient("", 15*time.Second)
	req, _ := http.NewRequest("GET", releasesAPI, nil)
	req.Header.Set("Accept", "application/vnd.github+json")
	resp, err := cl.Do(req)
	if err != nil {
		updSet(func(u *UpdState) { u.Status = "GitHub не ответил — проверь интернет" })
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		updSet(func(u *UpdState) { u.Status = fmt.Sprintf("GitHub ответил HTTP %d, попробуй позже", resp.StatusCode) })
		return nil, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	var rels []struct {
		Tag        string `json:"tag_name"`
		Body       string `json:"body"`
		Draft      bool   `json:"draft"`
		Prerelease bool   `json:"prerelease"`
		Assets     []struct {
			Name string `json:"name"`
			URL  string `json:"browser_download_url"`
			Size int64  `json:"size"`
		} `json:"assets"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&rels); err != nil {
		return nil, err
	}
	cur := buildNum(appVersion)
	var best *UpdateInfo
	var notes []string
	for _, r := range rels {
		if r.Draft || r.Prerelease || !strings.HasPrefix(r.Tag, "pc-v") {
			continue
		}
		v := strings.TrimPrefix(r.Tag, "pc-v")
		if buildNum(v) <= cur {
			continue
		}
		for _, a := range r.Assets {
			if a.Name == setupAsset {
				if best == nil || buildNum(v) > buildNum(best.Version) {
					best = &UpdateInfo{Version: v, URL: a.URL, Size: a.Size}
				}
				if n := cleanNotes(r.Body); n != "" && !contains(notes, n) {
					notes = append(notes, n)
				}
			}
		}
	}
	if best == nil {
		updSet(func(u *UpdState) { u.Available = nil; u.Status = "У тебя последняя версия " + appVersion })
		return nil, nil
	}
	if len(notes) == 0 {
		notes = []string{"Исправления и улучшения"}
	}
	best.Notes = "• " + strings.Join(notes, "\n• ")
	updSet(func(u *UpdState) { u.Available = best; u.Status = "Есть новая версия " + best.Version })
	logf("UPD", "доступна версия %s", best.Version)
	return best, nil
}

// installUpdate — скачать установщик и поставить тихо; приложение закроется и запустится заново.
func installUpdate(quit func()) error {
	u := updState().Available
	if u == nil {
		return errors.New("обновления нет")
	}
	dir := filepath.Join(os.TempDir(), "FastVPN-update")
	_ = os.MkdirAll(dir, 0o755)
	file := filepath.Join(dir, "FastVPN-setup-"+u.Version+".exe")
	if st, err := os.Stat(file); err != nil || st.Size() != u.Size {
		updSet(func(s *UpdState) { s.Progress = 0; s.Status = "Скачиваю обновление…" })
		if err := download(u.URL, file, u.Size); err != nil {
			updSet(func(s *UpdState) { s.Progress = -1; s.Status = "Не скачалось: " + shortErr(err) })
			return err
		}
	}
	updSet(func(s *UpdState) { s.Progress = 100; s.Status = "Устанавливаю…" })
	logf("UPD", "ставлю версию %s", u.Version)
	withSettings(func(s *Settings) { s.UpdNotes = u.Notes; s.UpdNotesFor = u.Version })
	exe, _ := os.Executable()
	// Установщик ждёт, пока мы закроемся, ставит тихо и запускает новую версию
	script := filepath.Join(dir, "update.cmd")
	cmd := fmt.Sprintf("@echo off\r\ntimeout /t 2 /nobreak >nul\r\n\"%s\" /S\r\nstart \"\" \"%s\"\r\n", file, exe)
	if err := os.WriteFile(script, []byte(cmd), 0o644); err != nil {
		return err
	}
	if err := runDetached("cmd", "/C", script); err != nil {
		return err
	}
	disconnect()
	quit()
	return nil
}

func download(url, file string, size int64) error {
	cl := &http.Client{Timeout: 10 * time.Minute}
	resp, err := cl.Get(url)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	part := file + ".part"
	f, err := os.Create(part)
	if err != nil {
		return err
	}
	var got int64
	buf := make([]byte, 256*1024)
	for {
		n, err := resp.Body.Read(buf)
		if n > 0 {
			if _, werr := f.Write(buf[:n]); werr != nil {
				f.Close()
				return werr
			}
			got += int64(n)
			if size > 0 {
				p := int(got * 100 / size)
				updSet(func(s *UpdState) { s.Progress = p })
			}
		}
		if err == io.EOF {
			break
		}
		if err != nil {
			f.Close()
			return err
		}
	}
	f.Close()
	return os.Rename(part, file)
}
