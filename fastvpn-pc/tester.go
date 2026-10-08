package main

import (
	"io"
	"net"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// Подбор сервера — как в Android-версии (обычный режим):
// пинг = время TCP-рукопожатия напрямую до сервера (3 замера: лучший — пинг, разница — разброс);
// работоспособность проверяется через ядро только у лучших; среди рабочих — самый быстрый по загрузке.

const checkURL = "http://www.gstatic.com/generate_204"

type Result struct {
	Ms, Jitter, Score int
}

var (
	resMu   sync.RWMutex
	results = map[string]*Result{} // nil — сервер не ответил
	speeds  = map[string]float64{} // Мбит/с
	measuring atomic.Bool
)

func clearResults() {
	resMu.Lock()
	results = map[string]*Result{}
	speeds = map[string]float64{}
	resMu.Unlock()
}

func getResult(tag string) (*Result, bool) {
	resMu.RLock()
	defer resMu.RUnlock()
	r, ok := results[tag]
	return r, ok
}

func getSpeed(tag string) float64 {
	resMu.RLock()
	defer resMu.RUnlock()
	return speeds[tag]
}

func setResult(tag string, r *Result) {
	resMu.Lock()
	results[tag] = r
	resMu.Unlock()
}

func rankedTags() []string {
	resMu.RLock()
	type kv struct {
		tag   string
		score int
	}
	var l []kv
	for t, r := range results {
		if r != nil {
			l = append(l, kv{t, r.Score})
		}
	}
	resMu.RUnlock()
	sort.Slice(l, func(i, j int) bool { return l[i].score < l[j].score })
	out := make([]string, len(l))
	for i, x := range l {
		out[i] = x.tag
	}
	return out
}

var ruWords = []string{"🇷🇺", "москва", "moscow", "россия", "russia", "санкт", "петербург", "spb", "msk"}

func isRussian(n Node) bool {
	if c, ok := getSettings().Exits[n.Tag]; ok {
		return c == "RU"
	}
	name := strings.ToLower(n.Name)
	for _, w := range ruWords {
		if strings.Contains(name, w) {
			return true
		}
	}
	return false
}

// isSeparator — строки-разделители в подписках («Локации под обход ⬇️»), не серверы.
func isSeparator(n Node) bool { return strings.Contains(n.Name, "⬇") || strings.Contains(n.Name, "⬆") }

func nameOf(tag string) string {
	if n := nodeByTag(tag); n != nil {
		return cleanName(*n)
	}
	return ""
}

func tcpRTT(addr string) int {
	t := time.Now()
	c, err := net.DialTimeout("tcp4", addr, 1500*time.Millisecond)
	if err != nil {
		return 0
	}
	c.Close()
	ms := int(time.Since(t).Milliseconds())
	if ms < 1 {
		ms = 1
	}
	return ms
}

// measure — пинг до всех серверов напрямую. Возвращает теги от лучшего к худшему (избранные первыми).
func measure(progress func(done, total int)) []string {
	list := usableNodes(false)
	var cand []Node
	for _, n := range list {
		if !isSeparator(n) {
			cand = append(cand, n)
		}
	}
	measuring.Store(true)
	defer measuring.Store(false)
	resMu.Lock()
	results = map[string]*Result{}
	resMu.Unlock()
	var done atomic.Int32
	sem := make(chan struct{}, 16)
	var wg sync.WaitGroup
	for _, n := range cand {
		wg.Add(1)
		sem <- struct{}{}
		go func(n Node) {
			defer wg.Done()
			defer func() { <-sem }()
			var samples []int
			if ips, err := net.LookupIP(n.Server); err == nil {
				var ip net.IP
				for _, x := range ips {
					if x.To4() != nil {
						ip = x
						break
					}
				}
				if ip != nil {
					addr := net.JoinHostPort(ip.String(), itoa(n.Port))
					for i := 0; i < 3; i++ {
						if ms := tcpRTT(addr); ms > 0 {
							samples = append(samples, ms)
						}
					}
				}
			}
			if len(samples) == 0 {
				setResult(n.Tag, nil)
			} else {
				best, worst := samples[0], samples[0]
				for _, s := range samples {
					best = min(best, s)
					worst = max(worst, s)
				}
				setResult(n.Tag, &Result{Ms: best, Jitter: worst - best, Score: best + (worst-best)/2})
			}
			if progress != nil {
				progress(int(done.Add(1)), len(cand))
			}
		}(n)
	}
	wg.Wait()
	ranked := rankedTags()
	logf("SRV", "пинг до серверов: ответили %d из %d", len(ranked), len(cand))
	// Избранные — в начало, порядок внутри сохраняется
	favs := getSettings().Favorites
	var fav, rest []string
	for _, t := range ranked {
		if n := nodeByTag(t); n != nil && contains(favs, n.Name) {
			fav = append(fav, t)
		} else {
			rest = append(rest, t)
		}
	}
	return append(fav, rest...)
}

func selectTag(tag string) {
	clashSelect(tag)
	withSettings(func(s *Settings) { s.SelectedTag = tag })
}

// pickWorking — первый по списку сервер, через который реально проходит запрос.
func pickWorking(ranked []string, maxTries int) string {
	for i, tag := range ranked {
		if i >= maxTries || !core.Running() {
			break
		}
		if !clashSelect(tag) {
			continue
		}
		if clashDelay(tag, checkURL, 4000) == 0 {
			logf("SRV", "подбор: «%s» — трафик не идёт, пропускаю", nameOf(tag))
			setResult(tag, nil)
			continue
		}
		logf("SRV", "подбор: выбран «%s»", nameOf(tag))
		selectTag(tag)
		return tag
	}
	logf("SRV", "подбор: рабочих серверов не нашлось")
	return ""
}

// quickDownload — короткий замер загрузки через VPN (Мбит/с).
func quickDownload(seconds int) float64 {
	proxy := core.MixedProxy()
	if proxy == "" {
		return 0
	}
	var total atomic.Int64
	deadline := time.Now().Add(time.Duration(seconds) * time.Second)
	start := time.Now()
	var wg sync.WaitGroup
	for i := 0; i < 4; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			cl := httpClient(proxy, 6*time.Second)
			for time.Now().Before(deadline) {
				resp, err := cl.Get("https://speed.cloudflare.com/__down?bytes=25000000")
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
		}()
	}
	wg.Wait()
	if total.Load() < 50_000 {
		return 0
	}
	return float64(total.Load()) * 8 / time.Since(start).Seconds() / 1e6
}

// pickFastest — среди лучших по пингу рабочих серверов самый быстрый по загрузке (2 с на сервер).
func pickFastest(ranked []string, candidates int, progress func(done, total int)) string {
	checked := 0
	best := ""
	bestSpeed := 0.0
	for i, tag := range ranked {
		if checked >= candidates || i >= candidates*2 || !core.Running() {
			break
		}
		if !clashSelect(tag) {
			continue
		}
		if clashDelay(tag, checkURL, 4000) == 0 {
			setResult(tag, nil)
			continue
		}
		checked++
		if progress != nil {
			progress(checked, candidates)
		}
		mbps := quickDownload(2)
		logf("SRV", "скорость: «%s» — %.1f Мбит/с", nameOf(tag), mbps)
		if mbps <= 0 {
			continue
		}
		resMu.Lock()
		speeds[tag] = mbps
		resMu.Unlock()
		if mbps > bestSpeed {
			bestSpeed, best = mbps, tag
		}
	}
	if best == "" {
		return pickWorking(ranked, 8)
	}
	selectTag(best)
	return best
}

// ---------------------------- страна выхода ----------------------------

var traceURLs = []string{
	"https://speed.cloudflare.com/cdn-cgi/trace",
	"https://www.cloudflare.com/cdn-cgi/trace",
	"https://1.1.1.1/cdn-cgi/trace",
}

// whoAmI — внешний IP и страна: через VPN (proxy) или напрямую ("").
func whoAmI(proxy string) (ip, country string) {
	cl := httpClient(proxy, 4*time.Second)
	for _, u := range traceURLs {
		resp, err := cl.Get(u)
		if err != nil {
			continue
		}
		b, _ := io.ReadAll(io.LimitReader(resp.Body, 8192))
		resp.Body.Close()
		f := map[string]string{}
		for _, line := range strings.Split(string(b), "\n") {
			if k, v, ok := strings.Cut(line, "="); ok {
				f[k] = strings.TrimSpace(v)
			}
		}
		if f["ip"] == "" {
			continue
		}
		loc := strings.ToUpper(f["loc"])
		if loc == "XX" {
			loc = ""
		}
		return f["ip"], loc
	}
	return "", ""
}

// exitCountryNow — страна выхода выбранного сейчас в ядре сервера (из кэша или проверкой).
func exitCountryNow(tag string) string {
	if c, ok := getSettings().Exits[tag]; ok {
		return c
	}
	_, loc := whoAmI(core.MixedProxy())
	if loc != "" {
		withSettings(func(s *Settings) { s.Exits[tag] = loc })
	}
	return loc
}

// ruCandidates — серверы, которые вероятнее всего выходят в Россию.
func ruCandidates(limit int) []Node {
	exits := getSettings().Exits
	var a, b, c []Node
	for _, n := range usableNodes(false) {
		if isSeparator(n) {
			continue
		}
		e, known := exits[n.Tag]
		switch {
		case known && e == "RU":
			a = append(a, n)
		case !known && isRussian(n):
			b = append(b, n)
		case !known:
			c = append(c, n)
		}
	}
	out := append(append(a, b...), c...)
	if len(out) > limit {
		out = out[:limit]
	}
	return out
}

// checkAllExits — страна выхода у всех серверов (кнопка «Выходы»). Нужен включённый VPN.
func checkAllExits(progress func(done, total int)) (found int) {
	keep := getSettings().SelectedTag
	defer func() {
		if keep != "" && core.Running() {
			clashSelect(keep)
		}
	}()
	var list []Node
	for _, n := range usableNodes(false) {
		if !isSeparator(n) {
			list = append(list, n)
		}
	}
	for i, n := range list {
		if !core.Running() {
			break
		}
		if progress != nil {
			progress(i+1, len(list))
		}
		if !clashSelect(n.Tag) {
			continue
		}
		if _, loc := whoAmI(core.MixedProxy()); loc != "" {
			found++
			withSettings(func(s *Settings) { s.Exits[n.Tag] = loc })
		}
	}
	return found
}

// switchNext — «Сменить сервер»: следующий рабочий по рейтингу после текущего (избранные первыми).
func switchNext() *Node {
	s := getSettings()
	var list []Node
	for _, n := range usableNodes(false) {
		if r, ok := getResult(n.Tag); !isSeparator(n) && !(ok && r == nil) {
			list = append(list, n)
		}
	}
	if len(list) < 2 {
		return nil
	}
	rank := map[string]int{}
	for i, t := range rankedTags() {
		rank[t] = i
	}
	pos := func(n Node) int {
		p := 1 << 20
		if r, ok := rank[n.Tag]; ok {
			p = r
		}
		if contains(s.Favorites, n.Name) {
			p -= 1 << 21
		}
		return p
	}
	sort.SliceStable(list, func(i, j int) bool { return pos(list[i]) < pos(list[j]) })
	cur := -1
	for i, n := range list {
		if n.Tag == s.SelectedTag {
			cur = i
		}
	}
	for step := 1; step <= min(5, len(list)); step++ {
		n := list[(cur+step+len(list))%len(list)]
		if n.Tag == s.SelectedTag || !clashSelect(n.Tag) {
			continue
		}
		if clashDelay(n.Tag, checkURL, 3000) > 0 {
			withSettings(func(st *Settings) { st.SelectedTag = n.Tag; st.AutoSelect = false })
			logf("SRV", "сменил сервер на «%s»", cleanName(n))
			return &n
		}
	}
	clashSelect(s.SelectedTag)
	return nil
}
