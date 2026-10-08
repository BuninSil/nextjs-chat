package main

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"regexp"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Подписка: скачивание по ссылке и разбор ссылок серверов (vless, trojan, vmess, ss, hysteria2, tuic)
// в outbound'ы sing-box. Логика та же, что в Android-версии.

type Node struct {
	Tag    string
	Name   string
	Type   string
	Server string
	Port   int
	// true — протокол с настоящим UDP
	NativeUDP bool
	Outbound  map[string]any
	// Ссылка для ядра Xray (XHTTP), если sing-box такой транспорт не умеет
	XrayLink string
}

type SubInfo struct {
	Upload, Download, Total, Expire int64
}

var (
	subMu   sync.RWMutex
	nodes   []Node
	subInfo *SubInfo
)

func allNodes() []Node {
	subMu.RLock()
	defer subMu.RUnlock()
	return nodes
}

// usableNodes — серверы без скрытых пользователем (includeHidden — со скрытыми).
func usableNodes(includeHidden bool) []Node {
	s := getSettings()
	var out []Node
	for _, n := range allNodes() {
		if includeHidden || !contains(s.Hidden, n.Name) {
			out = append(out, n)
		}
	}
	return out
}

func nodeByTag(tag string) *Node {
	for _, n := range allNodes() {
		if n.Tag == tag {
			n := n
			return &n
		}
	}
	return nil
}

func loadSubscription() {
	if b, err := os.ReadFile(dataFile("subscription.txt")); err == nil {
		p := parseAll(string(b))
		subMu.Lock()
		nodes = p
		subMu.Unlock()
	}
	if b, err := os.ReadFile(dataFile("subscription-info.txt")); err == nil {
		i := parseUserInfo(string(b))
		subMu.Lock()
		subInfo = &i
		subMu.Unlock()
	}
}

func getSubInfo() *SubInfo {
	subMu.RLock()
	defer subMu.RUnlock()
	return subInfo
}

// setNodes — новый список серверов. Страны выхода и подбор сбрасываются, только если он поменялся.
func setNodes(parsed []Node) {
	key := func(l []Node) string {
		var b strings.Builder
		for _, n := range l {
			fmt.Fprintf(&b, "%s|%s:%d\n", n.Name, n.Server, n.Port)
		}
		return b.String()
	}
	subMu.Lock()
	changed := key(parsed) != key(nodes)
	nodes = parsed
	subMu.Unlock()
	logf("SUB", "подписка обновлена: %d серверов", len(parsed))
	withSettings(func(s *Settings) {
		s.SubAt = time.Now().UnixMilli()
		if changed {
			s.Exits = map[string]string{}
			s.RankAt = 0
		}
	})
	if changed {
		clearResults()
	}
}

var userAgents = []string{
	"v2rayN/7.10.5", "Happ/3.6.0", "Hiddify/2.5.7", "Karing/1.1.2.606", "sing-box/1.11.4", "v2RayTun/5.1",
	"clash-verge/v2.0.3", "ClashMetaForAndroid/2.11.1.Meta", "v2rayNG/1.9.16", "Streisand", "FoXray",
}

// GeoBlocked — панель пускает только российские адреса, а запрос пришёл из другой страны.
type GeoBlocked struct{ Country string }

func (e *GeoBlocked) Error() string {
	return "Сервер подписки пускает только с российских адресов, а запрос пришёл из " + e.Country +
		" — скорее всего, через включённый VPN с зарубежным сервером.\n\nВыключи сторонний VPN или переключи его " +
		"на российский сервер и обнови ещё раз. Если в Fast VPN уже есть серверы и он подключён — он сам " +
		"скачает подписку через сервер с выходом в России."
}

var countryRe = regexp.MustCompile(`country code:\s*([A-Z]{2})`)
var tagsRe = regexp.MustCompile(`<[^>]+>`)
var spacesRe = regexp.MustCompile(`\s+`)

// fetchSubscription скачивает подписку (proxy — через VPN, "" — напрямую) и возвращает тело и заголовок userinfo.
func fetchSubscription(rawURL string, proxy string) (string, string, error) {
	client := httpClient(proxy, 20*time.Second)
	s := getSettings()
	lastProblem := "сервер подписки не ответил"
	for _, ua := range userAgents {
		req, err := http.NewRequest("GET", strings.TrimSpace(rawURL), nil)
		if err != nil {
			return "", "", errors.New("ссылка подписки кривая — проверь, что скопировал её целиком")
		}
		req.Header.Set("User-Agent", ua)
		req.Header.Set("Accept", "*/*")
		req.Header.Set("x-hwid", s.HWID)
		req.Header.Set("x-device-os", "Windows")
		req.Header.Set("x-ver-os", windowsVersion())
		req.Header.Set("x-device-model", "PC")
		resp, err := client.Do(req)
		if err != nil {
			logf("ERR", "подписка: попытка не удалась — %v", err)
			lastProblem = "сервер подписки не ответил (" + shortErr(err) + ")"
			continue
		}
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 20<<20))
		resp.Body.Close()
		logf("SUB", "ответ сервера подписки: HTTP %d", resp.StatusCode)
		if resp.StatusCode != 200 {
			why := strings.TrimSpace(spacesRe.ReplaceAllString(tagsRe.ReplaceAllString(string(body), " "), " "))
			if len(why) > 160 {
				why = why[:160]
			}
			if m := countryRe.FindStringSubmatch(why); resp.StatusCode == 403 && m != nil && m[1] != "RU" {
				logf("SUB", "сервер подписки пускает только российские адреса, запрос пришёл из %s", m[1])
				return "", "", &GeoBlocked{Country: m[1]}
			}
			lastProblem = fmt.Sprintf("сервер подписки ответил HTTP %d", resp.StatusCode)
			if why != "" {
				lastProblem += " («" + why + "»)"
			}
			continue
		}
		parsed := parseAll(string(body))
		logf("SUB", "получено %d байт, серверов распознано %d", len(body), len(parsed))
		if len(parsed) == 0 {
			lastProblem = "панель отдала подписку в формате, который я не понял"
			continue
		}
		return string(body), resp.Header.Get("subscription-userinfo"), nil
	}
	return "", "", errors.New(lastProblem + ".\n\nЕсли там про устройства (device / HWID / limit) — в боте или кабинете VPN " +
		"удали старое устройство или увеличь лимит. Обходной путь: вставь вместо ссылки сами серверы " +
		"(строки vless://, trojan://… или base64-блок).")
}

// updateSubscription скачивает и сохраняет подписку. Возвращает число серверов.
func updateSubscription(rawURL string) (int, error) {
	logf("SUB", "обновляю подписку")
	body, info, err := fetchSubscription(rawURL, "")
	var geo *GeoBlocked
	if errors.As(err, &geo) && core.Running() {
		body, info, err = fetchViaRuExit(rawURL)
	}
	if err != nil {
		return 0, err
	}
	return storeSubscription(body, info), nil
}

func storeSubscription(body, info string) int {
	parsed := parseAll(body)
	_ = os.WriteFile(dataFile("subscription.txt"), []byte(body), 0o644)
	if info != "" {
		_ = os.WriteFile(dataFile("subscription-info.txt"), []byte(info), 0o644)
		i := parseUserInfo(info)
		subMu.Lock()
		subInfo = &i
		subMu.Unlock()
	}
	setNodes(parsed)
	return len(parsed)
}

// importText — подписка, вставленная текстом (ссылки серверов или base64), а не URL.
func importText(text string) (int, error) {
	parsed := parseAll(text)
	if len(parsed) == 0 {
		return 0, errors.New("в тексте не нашлось ссылок серверов (vless://, trojan://, ss://…)")
	}
	_ = os.WriteFile(dataFile("subscription.txt"), []byte(text), 0o644)
	_ = os.Remove(dataFile("subscription-info.txt"))
	subMu.Lock()
	subInfo = nil
	subMu.Unlock()
	setNodes(parsed)
	return len(parsed), nil
}

// fetchViaRuExit — скачать подписку через сервер с выходом в России (для панелей, которые пускают только РФ).
func fetchViaRuExit(rawURL string) (string, string, error) {
	keep := getSettings().SelectedTag
	defer func() {
		if keep != "" {
			clashSelect(keep)
		}
	}()
	var last error = errors.New("не нашёл сервер с выходом в России")
	cands := ruCandidates(12)
	for _, n := range cands {
		if !clashSelect(n.Tag) || exitCountryNow(n.Tag) != "RU" {
			continue
		}
		logf("SUB", "пробую скачать через сервер с выходом в России")
		b, i, err := fetchSubscription(rawURL, core.MixedProxy())
		if err == nil {
			return b, i, nil
		}
		last = err
	}
	return "", "", last
}

func parseUserInfo(s string) SubInfo {
	m := map[string]int64{}
	for _, part := range strings.Split(s, ";") {
		kv := strings.SplitN(strings.TrimSpace(part), "=", 2)
		if len(kv) == 2 {
			v, _ := strconv.ParseInt(strings.TrimSpace(kv[1]), 10, 64)
			m[strings.TrimSpace(kv[0])] = v
		}
	}
	return SubInfo{Upload: m["upload"], Download: m["download"], Total: m["total"], Expire: m["expire"]}
}

// subWarning — скоро кончится срок или трафик. "" — всё в порядке.
func subWarning() string {
	i := getSubInfo()
	if i == nil {
		return ""
	}
	if i.Expire > 0 {
		days := float64(i.Expire*1000-time.Now().UnixMilli()) / 86_400_000
		if days <= 0 {
			return "Подписка закончилась — продли её у своего VPN-сервиса"
		}
		if days <= 3 {
			d := int(days + 0.999)
			w := "дня"
			if d == 1 {
				w = "день"
			}
			return fmt.Sprintf("Подписка заканчивается через %d %s — продли заранее", d, w)
		}
	}
	if i.Total > 0 && float64(i.Upload+i.Download) >= float64(i.Total)*0.9 {
		left := float64(i.Total-i.Upload-i.Download) / (1 << 30)
		if left < 0 {
			left = 0
		}
		return fmt.Sprintf("Трафик подписки почти кончился: осталось %.1f ГБ из %.0f ГБ", left, float64(i.Total)/(1<<30))
	}
	return ""
}

// ------------------------------- разбор -------------------------------

func b64(s string) (string, error) {
	clean := strings.NewReplacer("\n", "", "\r", "", " ", "").Replace(strings.TrimSpace(s))
	for _, enc := range []*base64.Encoding{base64.StdEncoding, base64.RawStdEncoding, base64.URLEncoding, base64.RawURLEncoding} {
		if b, err := enc.DecodeString(clean); err == nil {
			return string(b), nil
		}
	}
	return "", errors.New("not base64")
}

func parseAll(body string) []Node {
	if strings.HasPrefix(strings.TrimSpace(body), "{") {
		return parseSingBox(body)
	}
	text := body
	if !strings.Contains(body, "://") {
		if d, err := b64(body); err == nil {
			text = d
		}
	}
	var out []Node
	for _, line := range strings.Split(text, "\n") {
		l := strings.TrimSpace(line)
		if l == "" {
			continue
		}
		func() {
			defer func() { _ = recover() }() // кривые строки пропускаем, остальные серверы важнее
			if n := parseLink(l, fmt.Sprintf("n%d", len(out))); n != nil {
				out = append(out, *n)
			}
		}()
	}
	return out
}

// Формат sing-box (JSON): берём outbound'ы поддерживаемых типов как есть.
func parseSingBox(body string) []Node {
	var root struct {
		Outbounds []map[string]any `json:"outbounds"`
	}
	if json.Unmarshal([]byte(body), &root) != nil {
		return nil
	}
	types := map[string]bool{"vless": false, "trojan": false, "vmess": false, "shadowsocks": true, "hysteria2": true, "tuic": true}
	var out []Node
	for _, o := range root.Outbounds {
		t, _ := o["type"].(string)
		udp, ok := types[t]
		if !ok {
			continue
		}
		tag := fmt.Sprintf("n%d", len(out))
		name, _ := o["tag"].(string)
		if name == "" {
			name = tag
		}
		delete(o, "multiplex")
		delete(o, "detour")
		o["tag"] = tag
		server, _ := o["server"].(string)
		port := 0
		if p, ok := o["server_port"].(float64); ok {
			port = int(p)
		}
		if t == "shadowsocks" {
			t = "ss"
		}
		out = append(out, Node{Tag: tag, Name: name, Type: t, Server: server, Port: port, NativeUDP: udp, Outbound: o})
	}
	return out
}

// dec — раскодировать %XX, не превращая «+» в пробел.
func dec(s string) string {
	if d, err := url.PathUnescape(s); err == nil {
		return d
	}
	return s
}

// query — параметры ссылки без превращения «+» в пробел (как в Android).
func query(raw string) map[string]string {
	m := map[string]string{}
	for _, kv := range strings.Split(raw, "&") {
		if kv == "" {
			continue
		}
		k, v, _ := strings.Cut(kv, "=")
		k = dec(k)
		if _, seen := m[k]; !seen {
			m[k] = dec(v)
		}
	}
	return m
}

type qf func(string) string

func tlsOpts(q qf, server string, defaultOn bool) map[string]any {
	security := q("security")
	if security == "" {
		if defaultOn {
			security = "tls"
		} else {
			security = "none"
		}
	}
	if security == "none" {
		return nil
	}
	t := map[string]any{"enabled": true}
	sni := q("sni")
	if sni == "" {
		sni = q("peer")
	}
	if sni == "" {
		sni = server
	}
	t["server_name"] = sni
	if q("allowInsecure") == "1" || q("insecure") == "1" {
		t["insecure"] = true
	}
	if a := q("alpn"); a != "" {
		t["alpn"] = strings.Split(a, ",")
	}
	fp := q("fp")
	if security == "reality" {
		t["reality"] = map[string]any{"enabled": true, "public_key": q("pbk"), "short_id": q("sid")}
		if fp == "" {
			fp = "chrome"
		}
		t["utls"] = map[string]any{"enabled": true, "fingerprint": fp}
	} else if fp != "" {
		t["utls"] = map[string]any{"enabled": true, "fingerprint": fp}
	}
	return t
}

func orDefault(s, d string) string {
	if s == "" {
		return d
	}
	return s
}

func transportOpts(typ string, q qf) map[string]any {
	switch typ {
	case "grpc":
		return map[string]any{"type": "grpc", "service_name": q("serviceName")}
	case "ws":
		t := map[string]any{"type": "ws", "path": orDefault(q("path"), "/")}
		if h := q("host"); h != "" {
			t["headers"] = map[string]any{"Host": h}
		}
		return t
	case "http", "h2":
		t := map[string]any{"type": "http", "path": orDefault(q("path"), "/")}
		if h := q("host"); h != "" {
			t["host"] = strings.Split(h, ",")
		}
		return t
	case "httpupgrade":
		t := map[string]any{"type": "httpupgrade", "path": orDefault(q("path"), "/")}
		if h := q("host"); h != "" {
			t["host"] = h
		}
		return t
	}
	return nil // tcp
}

var xrayTransports = map[string]bool{"xhttp": true, "splithttp": true}

func parseLink(link, tag string) *Node {
	scheme, rest, ok := strings.Cut(link, "://")
	if !ok {
		return nil
	}
	scheme = strings.ToLower(scheme)
	switch scheme {
	case "vmess":
		return parseVmess(rest, tag)
	case "ss":
		return parseSS(rest, tag)
	}
	u, err := url.Parse(link)
	if err != nil || u.Hostname() == "" {
		return nil
	}
	params := query(u.RawQuery)
	q := func(k string) string { return params[k] }
	transportType := strings.ToLower(q("type"))
	name := dec(u.EscapedFragment())
	if name == "" {
		name = u.Hostname()
	}
	server := u.Hostname()
	port := 443
	if p, err := strconv.Atoi(u.Port()); err == nil && p > 0 {
		port = p
	}
	// XHTTP — через второе ядро Xray; outbound sing-box подставится при запуске (SOCKS к Xray)
	if xrayTransports[transportType] && (scheme == "vless" || scheme == "trojan") {
		ph := map[string]any{"tag": tag, "type": "socks", "server": "127.0.0.1", "server_port": 1, "version": "5"}
		return &Node{Tag: tag, Name: name, Type: "xhttp", Server: server, Port: port, Outbound: ph, XrayLink: link}
	}
	switch transportType {
	case "", "tcp", "grpc", "ws", "http", "h2", "httpupgrade":
	default:
		return nil // прочие транспорты только из xray (kcp и т.п.) не поддерживаем
	}
	user := ""
	if u.User != nil {
		user = u.User.Username()
		if p, ok := u.User.Password(); ok {
			user += ":" + p
		}
	}
	o := map[string]any{"tag": tag, "server": server, "server_port": port}
	switch scheme {
	case "vless":
		o["type"] = "vless"
		o["uuid"] = user
		if f := q("flow"); f != "" {
			o["flow"] = f
		}
		if t := tlsOpts(q, server, false); t != nil {
			o["tls"] = t
		}
		if t := transportOpts(transportType, q); t != nil {
			o["transport"] = t
		}
		o["packet_encoding"] = "xudp"
		return &Node{Tag: tag, Name: name, Type: "vless", Server: server, Port: port, Outbound: o}
	case "trojan":
		o["type"] = "trojan"
		o["password"] = user
		if t := tlsOpts(q, server, true); t != nil {
			o["tls"] = t
		}
		if t := transportOpts(transportType, q); t != nil {
			o["transport"] = t
		}
		return &Node{Tag: tag, Name: name, Type: "trojan", Server: server, Port: port, Outbound: o}
	case "hysteria2", "hy2":
		o["type"] = "hysteria2"
		o["password"] = user
		t := map[string]any{"enabled": true, "server_name": orDefault(q("sni"), server)}
		if q("insecure") == "1" {
			t["insecure"] = true
		}
		o["tls"] = t
		if ob := q("obfs"); ob != "" {
			o["obfs"] = map[string]any{"type": ob, "password": q("obfs-password")}
		}
		return &Node{Tag: tag, Name: name, Type: "hysteria2", Server: server, Port: port, NativeUDP: true, Outbound: o}
	case "tuic":
		id, pass, _ := strings.Cut(user, ":")
		o["type"] = "tuic"
		o["uuid"] = id
		o["password"] = pass
		if cc := q("congestion_control"); cc != "" {
			o["congestion_control"] = cc
		}
		t := map[string]any{"enabled": true, "server_name": orDefault(q("sni"), server)}
		if a := q("alpn"); a != "" {
			t["alpn"] = strings.Split(a, ",")
		}
		if q("allow_insecure") == "1" || q("insecure") == "1" {
			t["insecure"] = true
		}
		o["tls"] = t
		return &Node{Tag: tag, Name: name, Type: "tuic", Server: server, Port: port, NativeUDP: true, Outbound: o}
	}
	return nil
}

func parseVmess(rest, tag string) *Node {
	raw, err := b64(rest)
	if err != nil {
		return nil
	}
	var j map[string]any
	if json.Unmarshal([]byte(raw), &j) != nil {
		return nil
	}
	str := func(k string) string {
		switch v := j[k].(type) {
		case string:
			return v
		case float64:
			return strconv.Itoa(int(v))
		}
		return ""
	}
	server := str("add")
	if server == "" {
		return nil
	}
	port, err := strconv.Atoi(str("port"))
	if err != nil {
		port = 443
	}
	sec := "none"
	if str("tls") == "tls" {
		sec = "tls"
	}
	params := map[string]string{"security": sec, "sni": str("sni"), "fp": str("fp"), "alpn": str("alpn"),
		"serviceName": str("path"), "path": str("path"), "host": str("host")}
	q := func(k string) string { return params[k] }
	aid, _ := strconv.Atoi(str("aid"))
	o := map[string]any{"tag": tag, "type": "vmess", "server": server, "server_port": port, "uuid": str("id"),
		"alter_id": aid, "security": orDefault(str("scy"), "auto")}
	if t := tlsOpts(q, server, false); t != nil {
		o["tls"] = t
	}
	if t := transportOpts(str("net"), q); t != nil {
		o["transport"] = t
	}
	o["packet_encoding"] = "xudp"
	return &Node{Tag: tag, Name: orDefault(str("ps"), server), Type: "vmess", Server: server, Port: port, Outbound: o}
}

func parseSS(body, tag string) *Node {
	main, frag, _ := strings.Cut(body, "#")
	name := orDefault(dec(frag), tag)
	main, _, _ = strings.Cut(main, "?")
	var userInfo, hostPort string
	if i := strings.LastIndex(main, "@"); i >= 0 {
		raw := main[:i]
		if d, err := b64(raw); err == nil && strings.Contains(d, ":") {
			userInfo = d
		} else {
			userInfo = dec(raw)
		}
		hostPort = main[i+1:]
	} else {
		full, err := b64(main)
		if err != nil {
			return nil
		}
		i := strings.LastIndex(full, "@")
		if i < 0 {
			return nil
		}
		userInfo, hostPort = full[:i], full[i+1:]
	}
	j := strings.LastIndex(hostPort, ":")
	if j < 0 {
		return nil
	}
	server := strings.Trim(hostPort[:j], "[]")
	port, err := strconv.Atoi(strings.TrimRight(hostPort[j+1:], "/"))
	if err != nil {
		return nil
	}
	method, pass, _ := strings.Cut(userInfo, ":")
	o := map[string]any{"tag": tag, "type": "shadowsocks", "server": server, "server_port": port, "method": method, "password": pass}
	return &Node{Tag: tag, Name: name, Type: "ss", Server: server, Port: port, NativeUDP: true, Outbound: o}
}

// ------------------------- флаги и названия -------------------------

var flagRe = regexp.MustCompile(`[\x{1F1E6}-\x{1F1FF}]{2}`)
var leadFlagRe = regexp.MustCompile(`^[\x{1F1E6}-\x{1F1FF}]{2}\s*`)

// cleanName — название без флага в начале (флаг рисуем картинкой).
func cleanName(n Node) string { return strings.TrimSpace(leadFlagRe.ReplaceAllString(n.Name, "")) }

// countryOf — код страны для флага: из флага в названии, иначе из проверенной страны выхода.
func countryOf(n Node, exits map[string]string) string {
	if m := flagRe.FindString(n.Name); m != "" {
		r := []rune(m)
		return string([]rune{'a' + (r[0] - 0x1F1E6), 'a' + (r[1] - 0x1F1E6)})
	}
	if c, ok := exits[n.Tag]; ok {
		return strings.ToLower(c)
	}
	return ""
}

func shortErr(err error) string {
	s := err.Error()
	if i := strings.LastIndex(s, ": "); i >= 0 {
		s = s[i+2:]
	}
	return s
}

func windowsVersion() string {
	if runtime.GOOS == "windows" {
		return osVersion()
	}
	return runtime.GOOS
}
