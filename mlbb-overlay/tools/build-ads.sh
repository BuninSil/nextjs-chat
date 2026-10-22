#!/usr/bin/env bash
# Свежий список рекламы и трекеров для блока рекламы: HaGeZi Multi PRO (~200 тыс. доменов) +
# category-ads-all из v2fly, собранные в набор правил sing-box (.srs).
# Запуск: build-ads.sh <куда положить .srs>. Не скачалось — файл не трогаем (остаётся встроенный).
set -euo pipefail
OUT="$1"
SB_VER="1.11.4"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cd "$WORK"

curl -fsSL --retry 3 -o sb.tgz "https://github.com/SagerNet/sing-box/releases/download/v${SB_VER}/sing-box-${SB_VER}-linux-amd64.tar.gz"
tar xzf sb.tgz
SB="$WORK/sing-box-${SB_VER}-linux-amd64/sing-box"

curl -fsSL --retry 3 -o pro.txt "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/pro-onlydomains.txt"
curl -fsSL --retry 3 -o v2fly.srs "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-category-ads-all.srs"
"$SB" rule-set decompile v2fly.srs -o v2fly.json

python3 - <<'EOF'
import json
dom, suf = set(), set()
for r in json.load(open("v2fly.json"))["rules"]:
    dom |= set(r.get("domain", []))
    suf |= set(r.get("domain_suffix", []))
for line in open("pro.txt"):
    line = line.strip().lower()
    if line and not line.startswith("#"):
        suf.add(line)
dom = {d for d in dom if d not in suf}
# Защита от битой загрузки: в нормальном списке больше 100 тысяч доменов
assert len(suf) > 100_000, len(suf)
json.dump({"version": 2, "rules": [{"domain": sorted(dom), "domain_suffix": sorted(suf)}]}, open("ads.json", "w"))
print(f"реклама: {len(dom) + len(suf)} доменов")
EOF

"$SB" rule-set compile ads.json -o ads.srs
cp ads.srs "$OUT"
