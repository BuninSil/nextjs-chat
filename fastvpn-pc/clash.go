package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"time"
)

// Clash API ядра: переключение сервера, проверка «ходит ли трафик через сервер», счётчики трафика.

func clashReq(p Ports, method, path string, body any, timeout time.Duration) (*http.Response, error) {
	var rd *bytes.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rd = bytes.NewReader(b)
	} else {
		rd = bytes.NewReader(nil)
	}
	req, err := http.NewRequest(method, fmt.Sprintf("http://127.0.0.1:%d%s", p.API, path), rd)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+p.Secret)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	return (&http.Client{Timeout: timeout}).Do(req)
}

func clashVersionOK(p Ports) bool {
	r, err := clashReq(p, "GET", "/version", nil, 700*time.Millisecond)
	if err != nil {
		return false
	}
	r.Body.Close()
	return r.StatusCode == 200
}

// clashSelect — переключить селектор «proxy» на сервер.
func clashSelect(tag string) bool {
	if !core.Running() {
		return false
	}
	r, err := clashReq(core.Ports(), "PUT", "/proxies/proxy", map[string]string{"name": tag}, 3*time.Second)
	if err != nil {
		return false
	}
	r.Body.Close()
	return r.StatusCode >= 200 && r.StatusCode < 300
}

// clashDelay — задержка запроса через сервер (мс) или 0, если не прошло.
func clashDelay(tag, testURL string, timeoutMs int) int {
	if !core.Running() {
		return 0
	}
	path := "/proxies/" + url.PathEscape(tag) + "/delay?timeout=" + itoa(timeoutMs) + "&url=" + url.QueryEscape(testURL)
	r, err := clashReq(core.Ports(), "GET", path, nil, time.Duration(timeoutMs+1500)*time.Millisecond)
	if err != nil {
		return 0
	}
	defer r.Body.Close()
	if r.StatusCode != 200 {
		return 0
	}
	var v struct {
		Delay int `json:"delay"`
	}
	_ = json.NewDecoder(r.Body).Decode(&v)
	return v.Delay
}

// streamTraffic — поток скорости ядра (строка {"up":…,"down":…} раз в секунду), пока VPN работает.
func streamTraffic(onSecond func(up, down int64)) {
	p := core.Ports()
	req, err := http.NewRequest("GET", fmt.Sprintf("http://127.0.0.1:%d/traffic", p.API), nil)
	if err != nil {
		return
	}
	req.Header.Set("Authorization", "Bearer "+p.Secret)
	resp, err := (&http.Client{}).Do(req)
	if err != nil {
		return
	}
	defer resp.Body.Close()
	dec := json.NewDecoder(resp.Body)
	for core.Running() {
		var v struct {
			Up   int64 `json:"up"`
			Down int64 `json:"down"`
		}
		if dec.Decode(&v) != nil {
			return
		}
		onSecond(v.Up, v.Down)
	}
}

// clashTotals — сколько всего скачано/отдано через ядро (байты).
func clashTotals() (down, up int64, ok bool) {
	if !core.Running() {
		return 0, 0, false
	}
	r, err := clashReq(core.Ports(), "GET", "/connections", nil, 1500*time.Millisecond)
	if err != nil {
		return 0, 0, false
	}
	defer r.Body.Close()
	var v struct {
		DownloadTotal int64 `json:"downloadTotal"`
		UploadTotal   int64 `json:"uploadTotal"`
	}
	if json.NewDecoder(r.Body).Decode(&v) != nil {
		return 0, 0, false
	}
	return v.DownloadTotal, v.UploadTotal, true
}
