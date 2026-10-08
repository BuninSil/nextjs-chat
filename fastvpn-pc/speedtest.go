package main

import (
	"bytes"
	"io"
	"net/http"
	"sort"
	"sync"
	"sync/atomic"
	"time"
)

// Тест скорости через Cloudflare: пинг, загрузка и отдача в 4 потока. Если VPN включён — через него.

type SpeedState struct {
	Running bool    `json:"running"`
	Phase   string  `json:"phase"` // ping, down, up, done
	Ping    int     `json:"ping"`
	Jitter  int     `json:"jitter"`
	Down    float64 `json:"down"`
	Up      float64 `json:"up"`
	ViaVPN  bool    `json:"viaVpn"`
	Error   string  `json:"error"`
}

var (
	spMu sync.Mutex
	sp   SpeedState
)

func speedState() SpeedState { spMu.Lock(); defer spMu.Unlock(); return sp }

func spSet(f func(s *SpeedState)) { spMu.Lock(); f(&sp); spMu.Unlock() }

const speedBase = "https://speed.cloudflare.com"

func runSpeedTest() {
	spMu.Lock()
	if sp.Running {
		spMu.Unlock()
		return
	}
	proxy := core.MixedProxy()
	sp = SpeedState{Running: true, Phase: "ping", ViaVPN: proxy != ""}
	spMu.Unlock()
	logf("SPEED", "тест скорости (%s)", map[bool]string{true: "через VPN", false: "без VPN"}[proxy != ""])
	go func() {
		defer spSet(func(s *SpeedState) { s.Running = false; s.Phase = "done" })
		// Пинг: одно соединение переиспользуется, первые два замера (рукопожатия) выкидываем
		cl := &http.Client{Timeout: 8 * time.Second, Transport: httpClient(proxy, 8*time.Second).Transport}
		if tr, ok := cl.Transport.(*http.Transport); ok {
			tr.DisableKeepAlives = false
		}
		var samples []int
		for i := 0; i < 8; i++ {
			t := time.Now()
			resp, err := cl.Get(speedBase + "/__down?bytes=0")
			if err != nil {
				continue
			}
			_, _ = io.Copy(io.Discard, resp.Body)
			resp.Body.Close()
			samples = append(samples, int(time.Since(t).Milliseconds()))
		}
		if len(samples) > 2 {
			samples = samples[2:]
		}
		if len(samples) == 0 {
			spSet(func(s *SpeedState) { s.Error = "Cloudflare не ответил — проверь интернет" })
			return
		}
		sort.Ints(samples)
		spSet(func(s *SpeedState) {
			s.Ping = samples[len(samples)/2]
			s.Jitter = samples[len(samples)-1] - samples[0]
			s.Phase = "down"
		})
		d := parallelLoad(proxy, 7, false, func(v float64) { spSet(func(s *SpeedState) { s.Down = v }) })
		spSet(func(s *SpeedState) { s.Down = d; s.Phase = "up" })
		u := parallelLoad(proxy, 6, true, func(v float64) { spSet(func(s *SpeedState) { s.Up = v }) })
		spSet(func(s *SpeedState) { s.Up = u })
		logf("SPEED", "пинг %d ms, загрузка %.1f, отдача %.1f Мбит/с", samples[len(samples)/2], d, u)
	}()
}

// parallelLoad — загрузка (upload=false) или отдача в 4 потока, Мбит/с.
func parallelLoad(proxy string, seconds int, upload bool, progress func(float64)) float64 {
	var total atomic.Int64
	start := time.Now()
	deadline := start.Add(time.Duration(seconds) * time.Second)
	rate := func() float64 { return float64(total.Load()) * 8 / time.Since(start).Seconds() / 1e6 }
	stop := make(chan struct{})
	go func() {
		t := time.NewTicker(200 * time.Millisecond)
		defer t.Stop()
		for {
			select {
			case <-stop:
				return
			case <-t.C:
				progress(rate())
			}
		}
	}()
	var wg sync.WaitGroup
	for i := 0; i < 4; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			cl := httpClient(proxy, 8*time.Second)
			cl.Timeout = time.Duration(seconds+8) * time.Second
			chunk := bytes.Repeat([]byte{'0'}, 256*1024)
			for time.Now().Before(deadline) {
				if upload {
					pr, pw := io.Pipe()
					go func() {
						for j := 0; j < 16 && time.Now().Before(deadline); j++ {
							n, err := pw.Write(chunk)
							total.Add(int64(n))
							if err != nil {
								break
							}
						}
						pw.Close()
					}()
					resp, err := cl.Post(speedBase+"/__up", "application/octet-stream", pr)
					if err != nil {
						return
					}
					resp.Body.Close()
				} else {
					resp, err := cl.Get(speedBase + "/__down?bytes=50000000")
					if err != nil {
						return
					}
					buf := make([]byte, 64*1024)
					for time.Now().Before(deadline) {
						n, err := resp.Body.Read(buf)
						total.Add(int64(n))
						if err != nil {
							break
						}
					}
					resp.Body.Close()
				}
			}
		}()
	}
	wg.Wait()
	close(stop)
	if total.Load() < 50_000 {
		return 0
	}
	return rate()
}
