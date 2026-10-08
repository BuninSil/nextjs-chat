package main

import (
	"context"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"time"
)

func itoa(i int) string { return strconv.Itoa(i) }

func atoiDef(s string, d int) int {
	if v, err := strconv.Atoi(s); err == nil && v > 0 {
		return v
	}
	return d
}

func parseURL(s string) (*url.URL, error) { return url.Parse(s) }

// httpClient — клиент без переиспользования соединений (иначе после смены сервера запрос ушёл бы
// по уже открытому соединению через прошлый сервер). proxy — "socks5://127.0.0.1:port" или "" (напрямую).
func httpClient(proxy string, timeout time.Duration) *http.Client {
	tr := &http.Transport{
		DisableKeepAlives:   true,
		TLSHandshakeTimeout: timeout,
		DialContext:         (&net.Dialer{Timeout: timeout}).DialContext,
		Proxy:               nil,
	}
	if proxy != "" {
		if u, err := url.Parse(proxy); err == nil {
			tr.Proxy = http.ProxyURL(u)
		}
	}
	return &http.Client{Transport: tr, Timeout: timeout + 5*time.Second}
}

// freePort — свободный локальный порт.
func freePort() int {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0
	}
	defer l.Close()
	return l.Addr().(*net.TCPAddr).Port
}

func sleepCtx(ctx context.Context, d time.Duration) bool {
	select {
	case <-ctx.Done():
		return false
	case <-time.After(d):
		return true
	}
}
