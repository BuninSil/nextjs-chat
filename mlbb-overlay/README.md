# MLBB Server Overlay

Android-приложение (Kotlin, minSdk 26), которое показывает поверх Mobile Legends
(`com.mobile.legends`), к какому боевому серверу подключена игра.

## Как работает

- `CaptureVpnService` поднимает per-app VPN (`addAllowedApplication("com.mobile.legends")`),
  остальной трафик телефона VPN не трогает.
- Пакеты из tun пересылает наружу [zdtun](https://github.com/emanuele-f/zdtun)
  (тот же движок, что в PCAPdroid, LGPL-3.0, лежит в `app/src/main/cpp/zdtun`).
  Сокеты zdtun защищаются через `VpnService.protect()`.
- Каждое соединение логируется: IP, порт, протокол, байты/пакеты в обе стороны,
  время первого и последнего пакета, RTT TCP-хэндшейка.
- **Боевой сервер** = UDP-поток с максимумом пакетов за последние 10 с
  (порты 53/123/443 исключены — это DNS/NTP/QUIC).
- Геолокация — офлайн по DB-IP IP to City Lite (.mmdb), качается кнопкой в приложении
  или импортируется файлом.
- Пинг: ICMP echo → RTT TCP-хэндшейка игры к тому же IP → TCP-проба на :443.
- Оверлей: зелёный — Россия, красный — другая страна, серый — неизвестно.
  Перетаскивается пальцем, позиция запоминается.

## Сборка

CI: `.github/workflows/mlbb-overlay-apk.yml` собирает debug APK на каждый пуш в
`mlbb-overlay/**`, кладёт его в artifacts и в ветку `apk-builds`.

Локально нужны JDK 17 и Android SDK (platform 34, NDK 26.3.11579264, CMake 3.22.1):

```bash
cd mlbb-overlay
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
```

## Установка через adb

1. На телефоне: Настройки → О телефоне → 7 раз тапнуть «Номер сборки».
2. Настройки → Для разработчиков → включить «Отладка по USB».
3. Подключить кабелем, подтвердить RSA-ключ на телефоне.
4. `adb devices` — устройство должно быть в статусе `device`.
5. `adb install -r app-debug.apk`
6. Запустить «MLBB Server», нажать «Скачать базу», затем «Старт» и выдать разрешения
   (оверлей, уведомления, VPN).
