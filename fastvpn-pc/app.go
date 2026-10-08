package main

import (
	"context"
	"fmt"
	"sort"
	"strings"
	"sync/atomic"
	"time"

	"github.com/wailsapp/wails/v2/pkg/runtime"
)

// App — то, что видит окно (вызовы из интерфейса: window.go.main.App.*).
type App struct {
	ctx      context.Context
	quitting atomic.Bool
}

func NewApp() *App { return &App{} }

func (a *App) startup(ctx context.Context) {
	a.ctx = ctx
	logf("UI", "запуск Fast VPN %s, Windows %s", appVersion, osVersion())
	go trafficTicker()
	go startTray(a)
	s := getSettings()
	if s.ConnectOnStart && len(allNodes()) > 0 {
		connect()
	}
	if s.AutoUpdate {
		go func() {
			if u, _ := checkUpdate(); u != nil {
				trayNotify("Fast VPN " + u.Version + " — обнови в настройках")
			}
		}()
	}
	go func() {
		// Раз в сутки — свежая подписка (в фоне, молча)
		if getSettings().SubURL != "" && time.Since(time.UnixMilli(getSettings().SubAt)) > 20*time.Hour {
			if _, err := updateSubscription(getSettings().SubURL); err != nil {
				logf("SUB", "фоновое обновление не удалось: %v", err)
			}
		}
	}()
}

// beforeClose — крестик сворачивает в трей (если так настроено).
func (a *App) beforeClose(ctx context.Context) bool {
	if a.quitting.Load() || !getSettings().CloseToTray {
		disconnect()
		quitTray()
		return false
	}
	runtime.WindowHide(ctx)
	return true
}

func (a *App) Quit() {
	a.quitting.Store(true)
	disconnect()
	quitTray()
	runtime.Quit(a.ctx)
}

func (a *App) ShowWindow() {
	runtime.WindowShow(a.ctx)
	runtime.WindowUnminimise(a.ctx)
}

// ------------------------------ главный экран ------------------------------

type ServerInfo struct {
	Tag     string  `json:"tag"`
	Name    string  `json:"name"`
	Country string  `json:"country"`
	Type    string  `json:"type"`
	Ping    int     `json:"ping"`
	Jitter  int     `json:"jitter"`
	Dead    bool    `json:"dead"`
	Exit    string  `json:"exit"`
	Speed   float64 `json:"speed"`
	Fav     bool    `json:"fav"`
	Hidden  bool    `json:"hidden"`
	Sel     bool    `json:"sel"`
	Sep     bool    `json:"sep"`
}

type State struct {
	Running    bool        `json:"running"`
	Connecting bool        `json:"connecting"`
	Busy       bool        `json:"busy"`
	BusyText   string      `json:"busyText"`
	Error      string      `json:"error"`
	Server     *ServerInfo `json:"server"`
	Servers    int         `json:"servers"`
	AutoSelect bool        `json:"autoSelect"`
	IP         string      `json:"ip"`
	IPCountry  string      `json:"ipCountry"`
	IPLoading  bool        `json:"ipLoading"`
	Down       float64     `json:"down"`
	Up         float64     `json:"up"`
	SessionMB  float64     `json:"sessionMb"`
	SubWarn    string      `json:"subWarn"`
	Toast      string      `json:"toast"`
	Testing    bool        `json:"testing"`
	Version    string      `json:"version"`
	Update     UpdState    `json:"update"`
	Theme      string      `json:"theme"`
	Anim       string      `json:"anim"`
}

func (a *App) serverInfo(n Node, s Settings) ServerInfo {
	r, measured := getResult(n.Tag)
	si := ServerInfo{Tag: n.Tag, Name: cleanName(n), Country: countryOf(n, s.Exits), Type: n.Type,
		Exit: s.Exits[n.Tag], Speed: getSpeed(n.Tag), Fav: contains(s.Favorites, n.Name),
		Hidden: contains(s.Hidden, n.Name), Sel: n.Tag == s.SelectedTag, Sep: isSeparator(n)}
	if r != nil {
		si.Ping, si.Jitter = r.Ms, r.Jitter
	} else if measured {
		si.Dead = true
	}
	return si
}

func (a *App) GetState() State {
	s := getSettings()
	running := core.Running()
	refreshMyIP(false)
	st := State{
		Running:    running,
		Connecting: core.Starting() || (busy.Load() && !running),
		Busy:       busy.Load(),
		BusyText:   getBusy(),
		Error:      core.LastError(),
		Servers:    len(usableNodes(false)),
		AutoSelect: s.AutoSelect,
		SubWarn:    subWarning(),
		Toast:      takeToast(),
		Testing:    measuring.Load(),
		Version:    appVersion,
		Update:     updState(),
		Theme:      s.Theme,
		Anim:       s.Anim,
	}
	ipMu.Lock()
	st.IP, st.IPCountry, st.IPLoading = myIP, myCountry, ipLoading
	ipMu.Unlock()
	trafMu.Lock()
	st.Down, st.Up = downBps, upBps
	st.SessionMB = float64(sessionDown+sessionUp) / 1048576
	trafMu.Unlock()
	n := nodeByTag(s.SelectedTag)
	if n == nil {
		if l := usableNodes(false); len(l) > 0 {
			n = &l[0]
		}
	}
	if n != nil {
		si := a.serverInfo(*n, s)
		st.Server = &si
	}
	return st
}

func (a *App) Toggle()     { logf("UI", "нажал большую кнопку"); toggle() }
func (a *App) Connect()    { connect() }
func (a *App) Disconnect() { disconnect() }

func (a *App) RefreshIP() { logf("UI", "проверить IP заново"); refreshMyIP(true) }

// ---------------------------------- серверы ----------------------------------

func (a *App) GetServers(showHidden bool) []ServerInfo {
	s := getSettings()
	var out []ServerInfo
	for _, n := range usableNodes(true) {
		si := a.serverInfo(n, s)
		if si.Hidden && !showHidden {
			continue
		}
		out = append(out, si)
	}
	// Избранные сверху, дальше по пингу (не замеренные — в конце, мёртвые — в самом конце)
	key := func(x ServerInfo) int {
		switch {
		case x.Sep:
			return 1 << 29
		case x.Dead:
			return 1 << 28
		case x.Ping == 0:
			return 1 << 27
		}
		return x.Ping
	}
	sort.SliceStable(out, func(i, j int) bool {
		if out[i].Fav != out[j].Fav {
			return out[i].Fav
		}
		return key(out[i]) < key(out[j])
	})
	return out
}

func (a *App) SelectServer(tag string) {
	logf("UI", "выбрал сервер вручную: «%s», автовыбор выключен", nameOf(tag))
	withSettings(func(s *Settings) { s.SelectedTag = tag; s.AutoSelect = false })
	if core.Running() {
		clashSelect(tag)
		myIPInvalidate()
	}
	trayRefresh()
}

func (a *App) SetAutoSelect(on bool) {
	logf("SET", "Автовыбор = %v", on)
	withSettings(func(s *Settings) { s.AutoSelect = on; s.RankAt = 0 })
	if on && core.Running() && !busy.Load() {
		go func() {
			busy.Store(true)
			defer busy.Store(false)
			rank(true)
		}()
	}
}

func (a *App) ToggleFavorite(tag string) {
	n := nodeByTag(tag)
	if n == nil {
		return
	}
	withSettings(func(s *Settings) {
		if contains(s.Favorites, n.Name) {
			s.Favorites = without(s.Favorites, n.Name)
		} else {
			s.Favorites = append(s.Favorites, n.Name)
			s.Hidden = without(s.Hidden, n.Name)
		}
	})
}

func (a *App) ToggleHidden(tag string) {
	n := nodeByTag(tag)
	if n == nil {
		return
	}
	withSettings(func(s *Settings) {
		if contains(s.Hidden, n.Name) {
			s.Hidden = without(s.Hidden, n.Name)
		} else {
			s.Hidden = append(s.Hidden, n.Name)
			s.Favorites = without(s.Favorites, n.Name)
		}
	})
}

// MeasureAll — пинг до всех серверов (в фоне, ход — в busyText).
func (a *App) MeasureAll() {
	if busy.Load() {
		return
	}
	logf("UI", "нажал «Пинг» на экране серверов")
	go func() {
		busy.Store(true)
		defer busy.Store(false)
		measure(func(d, t int) { setBusy(fmt.Sprintf("Меряю пинг: %d из %d", d, t)) })
		setBusy("")
	}()
}

// CheckExits — страна выхода у всех серверов (нужен VPN).
func (a *App) CheckExits() string {
	if !core.Running() {
		return "Сначала подключи VPN — страну выхода видно только через сервер"
	}
	if busy.Load() {
		return "Подожди, идёт подбор сервера"
	}
	go func() {
		busy.Store(true)
		defer busy.Store(false)
		found := checkAllExits(func(d, t int) { setBusy(fmt.Sprintf("Проверяю выходы: %d из %d", d, t)) })
		setBusy("")
		toast(fmt.Sprintf("Страна выхода определена у %d серверов", found))
	}()
	return ""
}

func (a *App) SwitchNext() string {
	if !core.Running() {
		return "VPN не подключён"
	}
	if n := switchNext(); n != nil {
		myIPInvalidate()
		trayRefresh()
		return "Сервер: " + cleanName(*n)
	}
	return "Других рабочих серверов нет"
}

// --------------------------------- подписка ---------------------------------

type SubView struct {
	URL      string `json:"url"`
	Servers  int    `json:"servers"`
	Used     string `json:"used"`
	Expire   string `json:"expire"`
	Warning  string `json:"warning"`
	Updated  string `json:"updated"`
}

func (a *App) GetSubscription() SubView {
	s := getSettings()
	v := SubView{URL: s.SubURL, Servers: len(usableNodes(true)), Warning: subWarning()}
	if i := getSubInfo(); i != nil {
		used := float64(i.Upload+i.Download) / (1 << 30)
		if i.Total > 0 {
			v.Used = fmt.Sprintf("%.1f ГБ из %.0f ГБ", used, float64(i.Total)/(1<<30))
		} else {
			v.Used = fmt.Sprintf("%.1f ГБ (без лимита)", used)
		}
		if i.Expire > 0 {
			v.Expire = time.Unix(i.Expire, 0).Format("02.01.2006")
		}
	}
	if s.SubAt > 0 {
		v.Updated = time.UnixMilli(s.SubAt).Format("02.01.2006 15:04")
	}
	return v
}

// SaveSubscription — ссылка (http…) или сами серверы текстом. Возвращает сообщение.
func (a *App) SaveSubscription(text string) (string, error) {
	text = strings.TrimSpace(text)
	if text == "" {
		return "", fmt.Errorf("вставь ссылку подписки")
	}
	logf("UI", "сохранил подписку (%s)", map[bool]string{true: "ссылка", false: "текстом"}[strings.HasPrefix(text, "http")])
	var n int
	var err error
	if strings.HasPrefix(text, "http") {
		withSettings(func(s *Settings) { s.SubURL = text })
		n, err = updateSubscription(text)
	} else {
		withSettings(func(s *Settings) { s.SubURL = "" })
		n, err = importText(text)
	}
	if err != nil {
		return "", err
	}
	return fmt.Sprintf("Готово: %d серверов", n), nil
}

func (a *App) RefreshSubscription() (string, error) {
	u := getSettings().SubURL
	if u == "" {
		return "", fmt.Errorf("ссылки подписки нет — вставь её")
	}
	n, err := updateSubscription(u)
	if err != nil {
		return "", err
	}
	return fmt.Sprintf("Обновлено: %d серверов", n), nil
}

// --------------------------------- настройки ---------------------------------

func (a *App) GetSettings() Settings { return getSettings() }

// SetOption — переключатели настроек. Возвращает текст ошибки ("" — ок).
func (a *App) SetOption(key string, value any) string {
	logf("SET", "%s = %v", key, value)
	b, _ := value.(bool)
	str, _ := value.(string)
	vpnChanged := false
	switch key {
	case "ruDirect":
		withSettings(func(s *Settings) { s.RuDirect = b })
		vpnChanged = true
	case "adBlock":
		withSettings(func(s *Settings) { s.AdBlock = b })
		vpnChanged = true
	case "theme":
		withSettings(func(s *Settings) { s.Theme = str })
	case "anim":
		withSettings(func(s *Settings) { s.Anim = str })
	case "autostart":
		if err := setAutostart(b); err != nil {
			return "Не получилось: " + err.Error()
		}
		withSettings(func(s *Settings) { s.Autostart = b })
	case "connectOnStart":
		withSettings(func(s *Settings) { s.ConnectOnStart = b })
	case "closeToTray":
		withSettings(func(s *Settings) { s.CloseToTray = b })
	case "autoUpdate":
		withSettings(func(s *Settings) { s.AutoUpdate = b })
	}
	if vpnChanged && core.Running() {
		// Правила маршрутизации меняются только перезапуском ядра — переподключаемся сами
		go func() {
			disconnect()
			connect()
		}()
	}
	return ""
}

func (a *App) CheckUpdate() string {
	if _, err := checkUpdate(); err != nil {
		return updState().Status
	}
	return updState().Status
}

func (a *App) InstallUpdate() string {
	if err := installUpdate(a.Quit); err != nil {
		return "Не получилось: " + err.Error()
	}
	return ""
}

func (a *App) Report() string {
	p, err := saveReport()
	if err != nil {
		return "Не получилось сохранить отчёт: " + err.Error()
	}
	openSelect(p)
	return "Отчёт сохранён: " + p + "\nОтправь этот файл разработчику любым удобным способом."
}

func (a *App) ExportBackup() string {
	p, err := runtime.SaveFileDialog(a.ctx, runtime.SaveDialogOptions{
		Title:           "Сохранить настройки",
		DefaultFilename: "fast-vpn-settings-" + time.Now().Format("20060102") + ".json",
		Filters:         []runtime.FileFilter{{DisplayName: "Настройки Fast VPN", Pattern: "*.json"}},
	})
	if err != nil || p == "" {
		return ""
	}
	if err := exportBackup(p); err != nil {
		return "Не получилось: " + err.Error()
	}
	return "Сохранено. Файл подходит и для телефона: Настройки → «Загрузить из файла»."
}

// PickBackup — выбрать файл; возвращает путь и описание для подтверждения.
func (a *App) PickBackup() map[string]any {
	p, err := runtime.OpenFileDialog(a.ctx, runtime.OpenDialogOptions{
		Title:   "Загрузить настройки",
		Filters: []runtime.FileFilter{{DisplayName: "Настройки Fast VPN", Pattern: "*.json"}},
	})
	if err != nil || p == "" {
		return nil
	}
	_, info, err := readBackup(p)
	if err != nil {
		return map[string]any{"error": err.Error()}
	}
	return map[string]any{"path": p, "created": info.Created, "app": info.App, "servers": info.Servers}
}

func (a *App) ImportBackup(path string) string {
	if err := importBackup(path); err != nil {
		return "Не получилось: " + err.Error()
	}
	return ""
}

// ------------------------------- тест скорости -------------------------------

func (a *App) SpeedTest() { logf("UI", "нажал «Тест скорости»"); runSpeedTest() }

func (a *App) SpeedState() SpeedState { return speedState() }

func (a *App) OpenURL(u string) { runtime.BrowserOpenURL(a.ctx, u) }

// ------------------------------ программы через VPN ------------------------------

type AppsView struct {
	Mode     int      `json:"mode"`
	Selected []string `json:"selected"`
	Running  []string `json:"running"`
}

// GetApps — режим, выбранные программы и запущенные сейчас (чтобы было из чего выбрать).
func (a *App) GetApps() AppsView {
	s := getSettings()
	return AppsView{Mode: s.AppsMode, Selected: orEmpty(s.AppsList), Running: runningProcesses()}
}

// SetApps — сохранить выбор; если VPN включён — переподключиться, чтобы применить.
func (a *App) SetApps(mode int, list []string) {
	logf("SET", "Программы через VPN: режим %d, выбрано %d", mode, len(list))
	withSettings(func(s *Settings) { s.AppsMode = mode; s.AppsList = list })
	if core.Running() {
		go func() {
			disconnect()
			connect()
		}()
	}
}

// RankBySpeed — «Скорость» на экране серверов: пинг до всех и самый быстрый по загрузке среди лучших.
func (a *App) RankBySpeed() string {
	if !core.Running() {
		return "Сначала подключи VPN — скорость меряется через сервер"
	}
	if busy.Load() {
		return "Подожди, идёт подбор сервера"
	}
	logf("UI", "нажал «Скорость» на экране серверов")
	go func() {
		busy.Store(true)
		defer busy.Store(false)
		ranked := measure(func(d, t int) { setBusy(fmt.Sprintf("Меряю пинг: %d из %d", d, t)) })
		if len(ranked) == 0 || !core.Running() {
			setBusy("")
			return
		}
		best := pickFastest(ranked, 8, func(d, t int) { setBusy(fmt.Sprintf("Проверяю скорость: %d из %d", d, t)) })
		setBusy("")
		myIPInvalidate()
		trayRefresh()
		if best != "" {
			toast(fmt.Sprintf("Самый быстрый: %s — %.0f Мбит/с", nameOf(best), getSpeed(best)))
		}
	}()
	return ""
}

// TakeUpdatedNotes — «что нового» один раз после обновления.
func (a *App) TakeUpdatedNotes() map[string]string {
	s := getSettings()
	if s.UpdNotesFor == "" || s.UpdNotesFor != appVersion {
		return nil
	}
	withSettings(func(st *Settings) { st.UpdNotes = ""; st.UpdNotesFor = "" })
	return map[string]string{"version": appVersion, "notes": s.UpdNotes}
}
