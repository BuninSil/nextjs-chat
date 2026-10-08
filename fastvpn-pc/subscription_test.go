package main

import (
	"encoding/base64"
	"encoding/json"
	"strings"
	"testing"
)

func TestParseLinks(t *testing.T) {
	vm, _ := json.Marshal(map[string]any{"add": "v.example.com", "port": "8443", "id": "u-1", "aid": "0", "net": "ws", "path": "/ws", "host": "h.example.com", "tls": "tls", "ps": "🇩🇪 Германия"})
	links := []string{
		"vless://11111111-2222@1.2.3.4:443?security=reality&pbk=AbC+d_e&sid=ab&sni=www.google.com&fp=chrome&flow=xtls-rprx-vision&type=tcp#%F0%9F%87%B3%F0%9F%87%B1%20Нидерланды",
		"trojan://pass%40word@t.example.com:443?type=grpc&serviceName=svc#Trojan",
		"hy2://secret@h.example.com:8443?sni=h.example.com&insecure=1#Hy2",
		"tuic://uuid:pw@t2.example.com:443?alpn=h3&congestion_control=bbr#Tuic",
		"ss://" + base64.RawURLEncoding.EncodeToString([]byte("aes-256-gcm:pw")) + "@5.6.7.8:8388#SS",
		"vmess://" + base64.StdEncoding.EncodeToString(vm),
		"vless://u@x.example.com:443?type=xhttp&security=tls&path=%2Fx#XHTTP",
		"vless://u@k.example.com:443?type=kcp#KCP",
	}
	body := base64.StdEncoding.EncodeToString([]byte(strings.Join(links, "\n")))
	got := parseAll(body)
	if len(got) != 7 {
		t.Fatalf("ожидал 7 серверов, получил %d", len(got))
	}
	v := got[0]
	tls := v.Outbound["tls"].(map[string]any)
	if v.Type != "vless" || v.Port != 443 || tls["reality"].(map[string]any)["public_key"] != "AbC+d_e" || v.Outbound["flow"] != "xtls-rprx-vision" {
		t.Fatalf("vless: %+v", v.Outbound)
	}
	if cleanName(v) != "Нидерланды" || countryOf(v, nil) != "nl" {
		t.Fatalf("имя/флаг: %q %q", cleanName(v), countryOf(v, nil))
	}
	if got[1].Outbound["password"] != "pass@word" || got[1].Outbound["transport"].(map[string]any)["service_name"] != "svc" {
		t.Fatalf("trojan: %+v", got[1].Outbound)
	}
	if got[3].Outbound["uuid"] != "uuid" || got[3].Outbound["password"] != "pw" {
		t.Fatalf("tuic: %+v", got[3].Outbound)
	}
	if got[4].Outbound["method"] != "aes-256-gcm" || got[4].Port != 8388 {
		t.Fatalf("ss: %+v", got[4].Outbound)
	}
	if got[5].Type != "vmess" || got[5].Port != 8443 || countryOf(got[5], nil) != "de" {
		t.Fatalf("vmess: %+v", got[5])
	}
	if got[6].XrayLink == "" || xrayOutbound(got[6].XrayLink, "n6") == nil {
		t.Fatalf("xhttp: %+v", got[6])
	}
	conf := buildBoxConfig(got, "n1", Ports{API: 1, Secret: "s", Mixed: 2}, map[string]int{"n6": 3}, *defaultSettings(), "/r", []string{"FastVPN.exe"})
	var m map[string]any
	if err := json.Unmarshal(conf, &m); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(conf), `"FastVPN.exe"`) || !strings.Contains(string(conf), `"default": "n1"`) {
		t.Fatal("конфиг без правила для самого приложения или выбора")
	}
}
