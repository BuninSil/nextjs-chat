package main

import (
	"sync"
	"sync/atomic"
	"time"
)

// Подключение — как в Android-версии: сразу к последнему удачному серверу и быстрая проверка;
// полный подбор только если сервер не отвечает или с прошлого прошло больше 6 часов (уже при работающем VPN).
// Пока VPN работает, сторож раз в полминуты проверяет сервер и при смерти переключает на рабочий.

const rankTTL = 6 * time.Hour

var (
	busy     atomic.Bool
	busyMu   sync.Mutex
	busyText string
	// Сообщение для всплывашки в окне (сервер, ошибка); забирается интерфейсом
	toastMu  sync.Mutex
	toastMsg string
)

func setBusy(text string) {
	busyMu.Lock()
	busyText = text
	busyMu.Unlock()
}

func getBusy() string {
	busyMu.Lock()
	defer busyMu.Unlock()
	return busyText
}

func toast(msg string) {
	toastMu.Lock()
	toastMsg = msg
	toastMu.Unlock()
}

func takeToast() string {
	toastMu.Lock()
	defer toastMu.Unlock()
	m := toastMsg
	toastMsg = ""
	return m
}

func connect() {
	if busy.Load() || core.Running() || core.Starting() {
		return
	}
	s := getSettings()
	logf("CONN", "подключаю: сервер «%s», автовыбор %v", nameOf(s.SelectedTag), s.AutoSelect)
	busy.Store(true)
	setBusy("Подключаюсь…")
	trayRefresh()
	go func() {
		defer func() {
			busy.Store(false)
			trayRefresh()
		}()
		t0 := time.Now()
		if err := core.Start(); err != nil {
			toast(err.Error())
			return
		}
		myIPInvalidate()
		logf("CONN", "VPN поднялся за %d мс", time.Since(t0).Milliseconds())
		afterStart()
		go watchdog()
	}()
}

func disconnect() {
	logf("CONN", "выключаю VPN")
	core.Stop()
	myIPInvalidate()
	trayRefresh()
}

func toggle() {
	if core.Running() || core.Starting() {
		disconnect()
	} else {
		connect()
	}
}

func afterStart() {
	s := getSettings()
	tag := s.SelectedTag
	if nodeByTag(tag) == nil {
		tag = ""
	}
	setBusy("Проверяю сервер…")
	alive := tag != "" && clashDelay(tag, checkURL, 3000) > 0
	stale := s.RankAt == 0 || time.Since(time.UnixMilli(s.RankAt)) > rankTTL
	logf("CONN", "проверка сервера «%s»: отвечает %v, подбор устарел %v", nameOf(tag), alive, stale)
	switch {
	case !s.AutoSelect:
		if alive {
			toast("Сервер: " + nameOf(tag))
		} else {
			toast("Выбранный сервер не отвечает — выбери другой в «Серверах» или включи автовыбор")
		}
	case alive && !stale:
		toast("Сервер: " + nameOf(tag))
	default:
		rank(alive)
	}
}

// rank — полный подбор при работающем VPN. working — текущий уже работает (подбор идёт фоном).
func rank(working bool) {
	if !core.Running() {
		return
	}
	setBusy("Меряю пинг до серверов…")
	ranked := measure(func(done, total int) { setBusy("Меряю пинг: " + itoa(done) + " из " + itoa(total)) })
	if !core.Running() || len(ranked) == 0 {
		return
	}
	if !working {
		setBusy("Ищу рабочий сервер…")
		pickWorking(ranked, 8)
		myIPInvalidate()
		toast("Сервер: " + nameOf(getSettings().SelectedTag))
	}
	pickFastest(ranked, 6, func(done, total int) { setBusy("Проверяю скорость: " + itoa(done) + " из " + itoa(total)) })
	myIPInvalidate()
	if core.Running() {
		withSettings(func(s *Settings) { s.RankAt = time.Now().UnixMilli() })
	}
}

var watchdogOn atomic.Bool

// watchdog — раз в 30 секунд проверяет сервер; два провала подряд — тихо переключает на рабочий.
func watchdog() {
	if !watchdogOn.CompareAndSwap(false, true) {
		return
	}
	defer watchdogOn.Store(false)
	fails := 0
	for core.Running() {
		time.Sleep(30 * time.Second)
		s := getSettings()
		if !core.Running() || !s.AutoSelect || busy.Load() {
			continue
		}
		if clashDelay(s.SelectedTag, checkURL, 4000) > 0 {
			fails = 0
			continue
		}
		fails++
		logf("CONN", "сторож: сервер «%s» не ответил (%d-й раз)", nameOf(s.SelectedTag), fails)
		if fails < 2 {
			continue
		}
		fails = 0
		busy.Store(true)
		setBusy("Сервер не отвечает — ищу рабочий…")
		ranked := measure(nil)
		if len(ranked) > 0 && core.Running() {
			pickWorking(ranked, 8)
			myIPInvalidate()
		}
		busy.Store(false)
	}
}

// onCoreStopped — ядро упало само.
func onCoreStopped() {
	myIPInvalidate()
	trayRefresh()
	toast("VPN отключился: " + core.LastError())
}

// ----------------------------- скорость сейчас -----------------------------

var (
	trafMu               sync.Mutex
	downBps, upBps       float64
	sessionDown, sessionUp int64
)

// trafficTicker — раз в секунду скорость ↓↑ по счётчикам ядра.
func trafficTicker() {
	var lastD, lastU int64
	var lastAt time.Time
	wasRunning := false
	for {
		time.Sleep(time.Second)
		d, u, ok := clashTotals()
		trafMu.Lock()
		if !ok {
			downBps, upBps = 0, 0
			if !core.Running() {
				wasRunning = false
			}
			trafMu.Unlock()
			continue
		}
		if !wasRunning {
			lastD, lastU, lastAt = d, u, time.Now()
			sessionDown, sessionUp = 0, 0
			wasRunning = true
			trafMu.Unlock()
			continue
		}
		sec := time.Since(lastAt).Seconds()
		dd, du := max(d-lastD, 0), max(u-lastU, 0)
		downBps, upBps = float64(dd)/sec, float64(du)/sec
		sessionDown += dd
		sessionUp += du
		lastD, lastU, lastAt = d, u, time.Now()
		trafMu.Unlock()
	}
}

// ----------------------------------- мой IP -----------------------------------

var (
	ipMu      sync.Mutex
	myIP      string
	myCountry string
	ipKey     = "?"
	ipAt      time.Time
	ipLoading bool
)

func myIPInvalidate() {
	ipMu.Lock()
	ipKey = "?"
	ipMu.Unlock()
}

// refreshMyIP — замер, если устарел (сменился сервер, VPN, прошло 5 минут) или force.
func refreshMyIP(force bool) {
	key := ""
	if core.Running() {
		key = getSettings().SelectedTag
	}
	ipMu.Lock()
	if ipLoading || core.Starting() || busy.Load() || (!force && key == ipKey && time.Since(ipAt) < 5*time.Minute) {
		ipMu.Unlock()
		return
	}
	ipLoading = true
	ipMu.Unlock()
	go func() {
		ip, c := whoAmI(core.MixedProxy())
		ipMu.Lock()
		myIP, myCountry, ipKey, ipAt, ipLoading = ip, c, key, time.Now(), false
		ipMu.Unlock()
		if ip != "" {
			// Сам адрес в журнал не пишем — он попадёт в отчёт
			via := "без VPN"
			if key != "" {
				via = "через VPN"
			}
			logf("NET", "мой IP: %s, страна %s", via, c)
		}
	}()
}
