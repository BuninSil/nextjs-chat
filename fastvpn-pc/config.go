package main

import (
	"encoding/json"
	"path/filepath"
	"strings"
)

// Конфиг sing-box для ПК — тот же, что в Android-версии в обычном режиме:
//   - TUN на весь компьютер, FakeIP DNS (без задержек на разрешение имён);
//   - выбор сервера через selector «proxy», переключаем его сами по замерам (Clash API);
//   - mixed-вход на 127.0.0.1 — для замеров через VPN (скорость, мой IP, страна выхода);
//   - само приложение и ядро Xray идут мимо туннеля (иначе петля).

var ruSuffixes = []string{
	"ru", "su", "xn--p1ai", "xn--80adxhks", "moscow", "xn--d1acj3b",
	// Банки и платежи
	"sberbank.com", "sber.ru", "tbank.ru", "tinkoff.ru", "vtb.ru", "alfabank.ru", "gazprombank.ru", "nspk.ru",
	"mironline.ru", "qiwi.com", "yoomoney.ru",
	// Госуслуги и госсервисы
	"gosuslugi.ru", "mos.ru", "nalog.gov.ru", "pfr.gov.ru",
	// Яндекс, VK, Mail.ru
	"yandex.net", "yandex.com", "yastatic.net", "ya.ru", "vk.com", "vk.me", "vkuser.net", "userapi.com",
	"vk-cdn.net", "vkuseraudio.net", "vkuservideo.net", "mycdn.me", "ok.ru", "mail.ru", "imgsmail.ru",
	// Маркетплейсы и сервисы
	"ozon.ru", "ozone.ru", "wildberries.ru", "wb.ru", "wbbasket.ru", "avito.ru", "avito.st", "kinopoisk.ru",
	"rutube.ru", "dzen.ru", "2gis.com", "kaspersky.com",
}

type Ports struct {
	API    int
	Secret string
	Mixed  int
}

type obj = map[string]any

// buildBoxConfig собирает config.json для sing-box. selfProcs — процессы мимо туннеля.
func buildBoxConfig(list []Node, selected string, p Ports, xrayPorts map[string]int, s Settings, rulesDir string, selfProcs []string) []byte {
	var outbounds []any
	var tags []string
	for _, n := range list {
		o := cloneMap(n.Outbound)
		if n.XrayLink != "" {
			port, ok := xrayPorts[n.Tag]
			if !ok {
				continue // Xray недоступен — сервер пропускаем
			}
			o["server_port"] = port
		}
		outbounds = append(outbounds, o)
		tags = append(tags, n.Tag)
	}
	if selected == "" || !contains(tags, selected) {
		if len(tags) > 0 {
			selected = tags[0]
		}
	}
	outbounds = append(outbounds,
		obj{"type": "selector", "tag": "proxy", "outbounds": tags, "default": selected, "interrupt_exist_connections": false},
		obj{"type": "direct", "tag": "direct"},
	)

	tun := obj{
		"type": "tun", "tag": "tun-in",
		"interface_name": "FastVPN",
		"address":        []string{"172.19.0.1/30"},
		"mtu":            9000,
		"auto_route":     true,
		"strict_route":   false,
		"stack":          "mixed",
	}
	mixed := obj{"type": "mixed", "tag": "mixed-in", "listen": "127.0.0.1", "listen_port": p.Mixed}

	rules := []any{
		obj{"action": "sniff", "timeout": "100ms"},
		obj{"protocol": "dns", "action": "hijack-dns"},
		obj{"ip_is_private": true, "outbound": "direct"},
		// Само приложение и Xray — напрямую: замеры пинга и соединения XHTTP-серверов
		obj{"process_name": selfProcs, "outbound": "direct"},
	}
	// Выбор программ (по имени exe), как «Приложения через VPN» на телефоне
	if len(s.AppsList) > 0 {
		switch s.AppsMode {
		case 1: // только выбранные через VPN, остальное напрямую
			rules = append(rules, obj{"process_name": s.AppsList, "invert": true, "outbound": "direct"})
		case 2: // все, кроме выбранных
			rules = append(rules, obj{"process_name": s.AppsList, "outbound": "direct"})
		}
	}
	var ruleSets []any
	addSet := func(tag, file string) {
		ruleSets = append(ruleSets, obj{"type": "local", "tag": tag, "format": "binary", "path": filepath.Join(rulesDir, file)})
	}
	if s.AdBlock {
		addSet("ads", "geosite-category-ads-all.srs")
		rules = append(rules, obj{"rule_set": []string{"ads"}, "action": "reject"})
	}
	if s.RuDirect {
		rules = append(rules, obj{"domain_suffix": ruSuffixes, "outbound": "direct"})
		addSet("ru", "geosite-category-ru.srs")
		rules = append(rules, obj{"rule_set": []string{"ru"}, "outbound": "direct"})
	}

	dns := obj{
		"servers": []any{
			obj{"tag": "remote", "address": "https://1.1.1.1/dns-query", "detour": "proxy"},
			obj{"tag": "local", "address": "local", "detour": "direct"},
			obj{"tag": "fakeip", "address": "fakeip"},
		},
		"rules": []any{
			obj{"outbound": "any", "server": "local"},
			obj{"query_type": []string{"A", "AAAA"}, "server": "fakeip"},
		},
		"fakeip":            obj{"enabled": true, "inet4_range": "198.18.0.0/15"},
		"independent_cache": true,
		"final":             "remote",
		"strategy":          "ipv4_only",
	}
	route := obj{"rules": rules, "final": "proxy", "auto_detect_interface": true}
	if len(ruleSets) > 0 {
		route["rule_set"] = ruleSets
	}
	conf := obj{
		"log":       obj{"level": "warn"},
		"dns":       dns,
		"inbounds":  []any{tun, mixed},
		"outbounds": outbounds,
		"route":     route,
		"experimental": obj{
			"clash_api": obj{"external_controller": "127.0.0.1:" + itoa(p.API), "secret": p.Secret},
		},
	}
	b, _ := json.MarshalIndent(conf, "", " ")
	return b
}

func cloneMap(m map[string]any) map[string]any {
	b, _ := json.Marshal(m)
	var out map[string]any
	_ = json.Unmarshal(b, &out)
	return out
}

// ------------------------------- Xray -------------------------------

// xrayOutbound — ссылка vless:// или trojan:// с XHTTP -> outbound Xray.
func xrayOutbound(link, tag string) obj {
	scheme, _, _ := strings.Cut(link, "://")
	u, err := parseURL(link)
	if err != nil || u.Hostname() == "" {
		return nil
	}
	params := query(u.RawQuery)
	q := func(k string) string { return params[k] }
	host := u.Hostname()
	port := atoiDef(u.Port(), 443)
	user := ""
	if u.User != nil {
		user = u.User.Username()
	}
	out := obj{"tag": tag}
	switch strings.ToLower(scheme) {
	case "vless":
		usr := obj{"id": user, "encryption": orDefault(q("encryption"), "none")}
		if f := q("flow"); f != "" {
			usr["flow"] = f
		}
		out["protocol"] = "vless"
		out["settings"] = obj{"vnext": []any{obj{"address": host, "port": port, "users": []any{usr}}}}
	case "trojan":
		out["protocol"] = "trojan"
		out["settings"] = obj{"servers": []any{obj{"address": host, "port": port, "password": user}}}
	default:
		return nil
	}
	security := q("security")
	if security == "" {
		if strings.EqualFold(scheme, "trojan") {
			security = "tls"
		} else {
			security = "none"
		}
	}
	stream := obj{"network": "xhttp", "security": security}
	sni := orDefault(q("sni"), orDefault(q("peer"), host))
	switch security {
	case "reality":
		stream["realitySettings"] = obj{"serverName": sni, "fingerprint": orDefault(q("fp"), "chrome"),
			"publicKey": q("pbk"), "shortId": q("sid"), "spiderX": q("spx")}
	case "tls":
		t := obj{"serverName": sni, "fingerprint": orDefault(q("fp"), "chrome")}
		if a := q("alpn"); a != "" {
			t["alpn"] = strings.Split(a, ",")
		}
		if q("allowInsecure") == "1" || q("insecure") == "1" {
			t["allowInsecure"] = true
		}
		stream["tlsSettings"] = t
	}
	x := obj{"path": orDefault(q("path"), "/"), "mode": orDefault(q("mode"), "auto")}
	if h := q("host"); h != "" {
		x["host"] = h
	}
	if e := q("extra"); e != "" {
		var extra map[string]any
		if json.Unmarshal([]byte(e), &extra) == nil {
			x["extra"] = extra
		}
	}
	stream["xhttpSettings"] = x
	out["streamSettings"] = stream
	return out
}

// buildXrayConfig — по локальному SOCKS-входу на каждый XHTTP-сервер.
func buildXrayConfig(links map[string]string, ports map[string]int) []byte {
	var inbounds, outbounds, rules []any
	for tag, link := range links {
		ob := xrayOutbound(link, tag)
		if ob == nil {
			continue
		}
		outbounds = append(outbounds, ob)
		inbounds = append(inbounds, obj{"tag": "in-" + tag, "listen": "127.0.0.1", "port": ports[tag], "protocol": "socks",
			"settings": obj{"udp": true, "auth": "noauth"}})
		rules = append(rules, obj{"type": "field", "inboundTag": []string{"in-" + tag}, "outboundTag": tag})
	}
	outbounds = append(outbounds, obj{"tag": "direct", "protocol": "freedom"})
	b, _ := json.MarshalIndent(obj{
		"log":       obj{"loglevel": "warning"},
		"inbounds":  inbounds,
		"outbounds": outbounds,
		"routing":   obj{"rules": rules},
	}, "", " ")
	return b
}
