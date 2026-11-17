# agy-ui

Android-обёртка над официальным Google Antigravity CLI (`agy`).

Неофициальный проект. Не связан с Google. Использование `agy` регулируется
условиями Google Antigravity.

## Что внутри

- `AgyBinary` — скачивает официальный `android_arm64` бинарь по манифесту
  Google, проверяет sha512, кладёт в `files/bin/agy`. В APK бинарь не входит.
- `AgyLogin` — вход силами приложения: запускает `agy` с пустым `HOME`,
  показывает ссылку Google и отправляет вставленный код в stdin процесса
  (redirect_uri у Google ведёт на веб-страницу, а не на localhost).
- `AgyRunner` — headless-режим `agy -p … --output-format stream-json`,
  поток событий → UI.
- `Store` — сессии и сообщения в песочнице приложения (`sessions.json`).
- `NetConfig` / `Http` / `DohDns` — прокси (HTTP/SOCKS5) и DoH-резолвер
  для самого приложения.
- `NetDiag` — диагностика: имя, TLS или IP.
- UI — Material 3: список чатов, чат со стримом и карточками инструментов,
  режимы Plan / Auto / Правки, настройки сети доступны до входа.

## Сборка

Локально SDK не нужен: APK собирается в GitHub Actions
(`.github/workflows/android.yml`), артефакт `agy-ui-debug`.

Для локальной сборки нужен JDK 17 + Android SDK (build-tools, platform 35).

## Дальше

1. Зафиксировать схему `stream-json` по живому прогону (`agy-help.txt`, `agy-stream.txt`).
2. Режим ACP (`agy acp`) — permission-запросы и диффы с Accept/Reject.
3. Прокси для трафика самого `agy` — по результатам диагностики. DNS к обходу
   блокировок отношения не имеет и в схеме обхода не участвует.
