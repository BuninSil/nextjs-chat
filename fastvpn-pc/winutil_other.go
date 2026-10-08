//go:build !windows

package main

import (
	"errors"
	"os/exec"
)

// Заглушки, чтобы разбор подписки и конфиг можно было проверять тестами не на Windows.

func hideWindow(cmd *exec.Cmd)                   {}
func killOrphans()                               {}
func osVersion() string                          { return "" }
func setAutostart(on bool) error                 { return errors.New("только Windows") }
func openPath(p string)                          {}
func openSelect(p string)                        {}
func runDetached(exe string, args ...string) error { return errors.New("только Windows") }
