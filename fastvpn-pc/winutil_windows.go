//go:build windows

package main

import (
	"fmt"
	"os"
	"os/exec"
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
