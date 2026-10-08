//go:build windows

package main

import (
	"fmt"
	"os"
	"os/exec"
	"sort"
	"strings"
	"syscall"

	"golang.org/x/sys/windows"
)

const createNoWindow = 0x08000000

func hideWindow(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: createNoWindow}
}

// killOrphans — ядра, оставшиеся от прошлого запуска (если приложение закрыли аварийно).
func killOrphans() {
	for _, name := range []string{coreExe, xrayExe} {
		c := exec.Command("taskkill", "/F", "/IM", name)
		hideWindow(c)
		_ = c.Run()
	}
}

func osVersion() string {
	v := windows.RtlGetVersion()
	return fmt.Sprintf("%d.%d.%d", v.MajorVersion, v.MinorVersion, v.BuildNumber)
}

const taskName = "Fast VPN"

// setAutostart — запуск вместе с Windows через планировщик (с правами администратора, без окна UAC).
func setAutostart(on bool) error {
	if !on {
		c := exec.Command("schtasks", "/Delete", "/F", "/TN", taskName)
		hideWindow(c)
		_ = c.Run()
		return nil
	}
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	c := exec.Command("schtasks", "/Create", "/F", "/TN", taskName, "/SC", "ONLOGON", "/RL", "HIGHEST",
		"/TR", fmt.Sprintf(`"%s" --tray`, exe))
	hideWindow(c)
	if out, err := c.CombinedOutput(); err != nil {
		return fmt.Errorf("%v: %s", err, out)
	}
	return nil
}

// openPath — открыть папку или файл в Проводнике.
func openPath(p string) {
	c := exec.Command("explorer", p)
	_ = c.Start()
}

// runningProcesses — имена запущенных программ (exe), без системных.
func runningProcesses() []string {
	c := exec.Command("tasklist", "/FO", "CSV", "/NH")
	hideWindow(c)
	out, err := c.Output()
	if err != nil {
		return nil
	}
	skip := map[string]bool{"svchost.exe": true, "system": true, "registry": true, "smss.exe": true, "csrss.exe": true,
		"wininit.exe": true, "services.exe": true, "lsass.exe": true, "winlogon.exe": true, "fontdrvhost.exe": true,
		"dwm.exe": true, "conhost.exe": true, "tasklist.exe": true, "system idle process": true, "memory compression": true,
		"runtimebroker.exe": true, "sihost.exe": true, "ctfmon.exe": true, "dllhost.exe": true, "wmiprvse.exe": true,
		coreExe: true, xrayExe: true, "fastvpn.exe": true}
	seen := map[string]bool{}
	var list []string
	for _, line := range strings.Split(string(out), "\n") {
		f := strings.Split(strings.TrimSpace(line), "\",\"")
		if len(f) < 2 {
			continue
		}
		name := strings.Trim(f[0], "\"")
		low := strings.ToLower(name)
		if name == "" || skip[low] || seen[low] || !strings.HasSuffix(low, ".exe") {
			continue
		}
		seen[low] = true
		list = append(list, name)
	}
	sort.Slice(list, func(i, j int) bool { return strings.ToLower(list[i]) < strings.ToLower(list[j]) })
	return list
}

// openSelect — показать файл в Проводнике (выделенным).
func openSelect(p string) {
	c := exec.Command("explorer", "/select,", p)
	_ = c.Start()
}

// runDetached — запустить программу отдельно от нас (установщик обновления).
func runDetached(exe string, args ...string) error {
	c := exec.Command(exe, args...)
	c.SysProcAttr = &syscall.SysProcAttr{CreationFlags: 0x00000008 | 0x00000200} // DETACHED_PROCESS | NEW_PROCESS_GROUP
	return c.Start()
}
