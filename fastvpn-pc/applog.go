package main

import (
	"fmt"
	"os"
	"strings"
	"sync"
	"time"
)

// Журнал действий (как в Android-версии): нажатия, подключение, замеры, ошибки — с точным временем.
// Хранится последний ~1 МБ, уходит в «Сообщить о проблеме». Адресов серверов и ссылки подписки в нём нет.

const logMax = 1 << 20

var logMu sync.Mutex

func logf(cat, format string, a ...any) {
	line := fmt.Sprintf("%s [%s] %s\n", time.Now().Format("2006-01-02 15:04:05.000"), cat, fmt.Sprintf(format, a...))
	logMu.Lock()
	defer logMu.Unlock()
	f, err := os.OpenFile(dataFile("applog.txt"), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
	if err != nil {
		return
	}
	_, _ = f.WriteString(line)
	st, _ := f.Stat()
	f.Close()
	if st != nil && st.Size() > logMax {
		b, err := os.ReadFile(dataFile("applog.txt"))
		if err == nil && len(b) > logMax/2 {
			b = b[len(b)-logMax/2:]
			if i := strings.IndexByte(string(b), '\n'); i >= 0 {
				b = b[i+1:]
			}
			_ = os.WriteFile(dataFile("applog.txt"), b, 0o644)
		}
	}
}

func readLog() string {
	logMu.Lock()
	defer logMu.Unlock()
	b, _ := os.ReadFile(dataFile("applog.txt"))
	return string(b)
}
