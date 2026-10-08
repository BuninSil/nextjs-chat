package main

import (
	"bytes"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"
)

// Ядро VPN: sing-box (и Xray для XHTTP-серверов) отдельными процессами, без окон.
// Бинарники вшиты в приложение и распаковываются в папку данных при первом запуске версии.

const (
	coreExe = "fastvpn-core.exe" // sing-box
	xrayExe = "fastvpn-xray.exe" // Xray
)

type Core struct {
	mu       sync.Mutex
	cmd      *exec.Cmd
	xray     *exec.Cmd
	ports    Ports
	running  bool
	starting bool
	stopping bool
	lastErr  string
	since    time.Time
}

var core = &Core{}

func (c *Core) Running() bool  { c.mu.Lock(); defer c.mu.Unlock(); return c.running }
func (c *Core) Starting() bool { c.mu.Lock(); defer c.mu.Unlock(); return c.starting }
func (c *Core) LastError() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.lastErr
}
func (c *Core) Ports() Ports { c.mu.Lock(); defer c.mu.Unlock(); return c.ports }

// MixedProxy — адрес входа ядра для запросов через VPN ("" — VPN выключен).
func (c *Core) MixedProxy() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	if !c.running {
		return ""
	}
	return fmt.Sprintf("socks5://127.0.0.1:%d", c.ports.Mixed)
}

func (c *Core) setErr(e string) {
	c.mu.Lock()
	c.lastErr = e
	c.mu.Unlock()
}

// binDir — распаковать вшитые ядра (один раз на версию приложения).
func binDir() (string, error) {
	dir := filepath.Join(dataDir(), "bin", appVersion)
	stamp := filepath.Join(dir, "ok")
	if _, err := os.Stat(stamp); err == nil {
		return dir, nil
	}
	_ = os.MkdirAll(dir, 0o755)
	for name, data := range embeddedBins() {
		if len(data) == 0 {
			continue
		}
		if err := os.WriteFile(filepath.Join(dir, name), data, 0o755); err != nil {
			return "", fmt.Errorf("не удалось распаковать ядро VPN: %v", err)
		}
	}
	_ = os.WriteFile(stamp, []byte(appVersion), 0o644)
	// Старые версии ядер больше не нужны
	if entries, err := os.ReadDir(filepath.Join(dataDir(), "bin")); err == nil {
		for _, e := range entries {
			if e.Name() != appVersion {
				_ = os.RemoveAll(filepath.Join(dataDir(), "bin", e.Name()))
			}
		}
	}
	return dir, nil
}

// rulesDir — наборы правил (российские сайты, реклама).
func rulesDir() string {
	dir := filepath.Join(dataDir(), "rules")
	_ = os.MkdirAll(dir, 0o755)
	for name, data := range embeddedRules() {
		p := filepath.Join(dir, name)
		if st, err := os.Stat(p); err != nil || st.Size() != int64(len(data)) {
			_ = os.WriteFile(p, data, 0o644)
		}
	}
	return dir
}

// Start поднимает VPN и ждёт, пока ядро ответит (до 20 с).
func (c *Core) Start() error {
	c.mu.Lock()
	if c.running || c.starting {
		c.mu.Unlock()
		return nil
	}
	c.starting = true
	c.stopping = false
	c.lastErr = ""
	c.mu.Unlock()
	err := c.start()
	c.mu.Lock()
	c.starting = false
	if err != nil {
		c.lastErr = err.Error()
	}
	c.mu.Unlock()
	if err != nil {
		logf("VPN", "не подключилось: %v", err)
		c.killAll()
	}
	return err
}

func (c *Core) start() error {
	list := usableNodes(false)
	if len(list) == 0 {
		return errors.New("нет серверов: добавь ссылку подписки")
	}
	dir, err := binDir()
	if err != nil {
		return err
	}
	killOrphans()
	secretB := make([]byte, 12)
	_, _ = rand.Read(secretB)
	p := Ports{API: freePort(), Secret: hex.EncodeToString(secretB), Mixed: freePort()}

	// XHTTP-серверы — через Xray, каждому свой локальный SOCKS-порт
	xrayLinks := map[string]string{}
	xrayPorts := map[string]int{}
	if _, err := os.Stat(filepath.Join(dir, xrayExe)); err == nil {
		for _, n := range list {
			if n.XrayLink != "" {
				xrayLinks[n.Tag] = n.XrayLink
				xrayPorts[n.Tag] = freePort()
			}
		}
	}
	if len(xrayLinks) > 0 {
		xc := dataFile("xray.json")
		_ = os.WriteFile(xc, buildXrayConfig(xrayLinks, xrayPorts), 0o644)
		x := exec.Command(filepath.Join(dir, xrayExe), "run", "-c", xc)
		x.Dir = dataDir()
		hideWindow(x)
		pipeLog(x, "xray")
		if err := x.Start(); err != nil {
			logf("ERR", "Xray не запустился: %v", err)
		} else {
			c.mu.Lock()
			c.xray = x
			c.mu.Unlock()
			go func() { _ = x.Wait() }()
		}
	}

	s := getSettings()
	self, _ := os.Executable()
	selfProcs := []string{filepath.Base(self), xrayExe}
	conf := buildBoxConfig(list, s.SelectedTag, p, xrayPorts, s, rulesDir(), selfProcs)
	cf := dataFile("config.json")
	if err := os.WriteFile(cf, conf, 0o600); err != nil {
		return err
	}
	work := filepath.Join(dataDir(), "box")
	_ = os.MkdirAll(work, 0o755)
	cmd := exec.Command(filepath.Join(dir, coreExe), "run", "-c", cf, "-D", work)
	cmd.Dir = work
	hideWindow(cmd)
	fatal := pipeLog(cmd, "core")
	logf("VPN", "старт ядра: серверов %d, выбран «%s»", len(list), nameOf(s.SelectedTag))
	if err := cmd.Start(); err != nil {
		return fmt.Errorf("ядро VPN не запустилось: %v", err)
	}
	exited := make(chan struct{})
	c.mu.Lock()
	c.cmd = cmd
	c.ports = p
	c.mu.Unlock()
	go func() {
		_ = cmd.Wait()
		close(exited)
		c.mu.Lock()
		was := c.running
		stopping := c.stopping
		c.running = false
		if c.cmd == cmd {
			c.cmd = nil
		}
		c.mu.Unlock()
		if was && !stopping {
			msg := "ядро VPN остановилось"
			if f := fatal(); f != "" {
				msg += ": " + f
			}
			c.setErr(msg)
			logf("ERR", "%s", msg)
			c.killAll()
			onCoreStopped()
		}
	}()

	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) {
		select {
		case <-exited:
			f := fatal()
			if f == "" {
				f = "ядро VPN сразу закрылось"
			}
			return errors.New(explainCoreError(f))
		default:
		}
		if clashVersionOK(p) {
			c.mu.Lock()
			c.running = true
			c.since = time.Now()
			c.mu.Unlock()
			logf("VPN", "VPN поднялся")
			return nil
		}
		time.Sleep(150 * time.Millisecond)
	}
	return errors.New("VPN не подключился за 20 секунд")
}

// explainCoreError — понятный текст для частых ошибок ядра.
func explainCoreError(f string) string {
	l := strings.ToLower(f)
	switch {
	case strings.Contains(l, "access is denied") || strings.Contains(l, "administrator") || strings.Contains(l, "elevat"):
		return "Нужны права администратора: закрой Fast VPN и запусти снова (Windows спросит разрешение)"
	case strings.Contains(l, "wintun") || strings.Contains(l, "configure tun"):
		return "Windows не дала создать VPN-адаптер. Закрой другие VPN-программы и попробуй ещё раз. (" + f + ")"
	}
	return f
}

// Stop выключает VPN.
func (c *Core) Stop() {
	c.mu.Lock()
	c.stopping = true
	was := c.running
	c.running = false
	c.mu.Unlock()
	c.killAll()
	if was {
		logf("VPN", "VPN выключен")
	}
}

func (c *Core) killAll() {
	c.mu.Lock()
	cmd, x := c.cmd, c.xray
	c.cmd, c.xray = nil, nil
	c.mu.Unlock()
	for _, p := range []*exec.Cmd{cmd, x} {
		if p != nil && p.Process != nil {
			_ = p.Process.Kill()
		}
	}
}

// pipeLog — строки ядра в журнал (CORE). Возвращает функцию «последняя ошибка ядра».
func pipeLog(cmd *exec.Cmd, name string) func() string {
	w := &lineLog{name: name}
	cmd.Stdout = w
	cmd.Stderr = w
	return w.lastErr
}

var ansiRe = regexp.MustCompile(`\x1b\[[0-9;]*m`)

type lineLog struct {
	mu   sync.Mutex
	name string
	buf  []byte
	last string
	n    int
}

func (l *lineLog) Write(p []byte) (int, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.buf = append(l.buf, p...)
	for {
		i := bytes.IndexByte(l.buf, '\n')
		if i < 0 {
			break
		}
		line := strings.TrimSpace(ansiRe.ReplaceAllString(string(l.buf[:i]), ""))
		l.buf = l.buf[i+1:]
		if line == "" {
			continue
		}
		if strings.Contains(line, "FATAL") || strings.Contains(line, "ERROR") {
			l.last = line
			if j := strings.Index(l.last, "] "); j >= 0 {
				l.last = l.last[j+2:]
			}
		}
		if l.n < 2000 { // не забиваем журнал болтовнёй ядра
			logf("CORE", "%s: %s", l.name, line)
			l.n++
		}
	}
	if len(l.buf) > 64*1024 {
		l.buf = l.buf[:0]
	}
	return len(p), nil
}

func (l *lineLog) lastErr() string { l.mu.Lock(); defer l.mu.Unlock(); return l.last }
