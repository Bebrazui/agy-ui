#!/data/data/com.termux/files/usr/bin/bash
#
# agy-ui: пуш кода и получение APK из GitHub Actions.
#
#   bash tools/ci.sh            # запушить и дождаться сборки, скачать APK
#   bash tools/ci.sh push       # только пуш
#   bash tools/ci.sh status     # последний прогон
#   bash tools/ci.sh apk        # скачать APK последней успешной сборки
#
# Токен спрашивается один раз и никуда не сохраняется.

set -uo pipefail

REPO="Bebrazui/agy-ui"
BRANCH="main"
PROJ="$(cd "$(dirname "$0")/.." && pwd)"
API="https://api.github.com/repos/$REPO"

need() { command -v "$1" >/dev/null || { echo "Нет $1. Поставь: pkg install $2 -y"; exit 1; }; }
need git git
need curl curl
need unzip unzip

TOKEN="${GITHUB_TOKEN:-}"
if [ -z "$TOKEN" ]; then
    printf 'GitHub token (скрыт): '
    read -rs TOKEN
    echo
fi
if [ -z "$TOKEN" ]; then
    echo "Без токена никак."; exit 1
fi

auth() { printf 'Authorization: Bearer %s' "$TOKEN"; }

push_code() {
    echo "→ Пушу $BRANCH в $REPO"
    # Токен идёт в заголовке, в .git/config не попадает.
    if ! git -C "$PROJ" -c credential.helper= \
        -c http.extraHeader="Authorization: Basic $(printf 'x-access-token:%s' "$TOKEN" | base64 -w0)" \
        push "https://github.com/$REPO.git" "$BRANCH:$BRANCH"; then
        echo "Push не прошёл. Проверь токен (нужен scope repo) и права на репозиторий."
        exit 1
    fi
}

run_id() {
    curl -s -H "$(auth)" "$API/actions/runs?branch=$BRANCH&per_page=1" \
        | tr ',' '\n' | grep -m1 '"id"' | tr -dc '0-9'
}

run_field() {
    curl -s -H "$(auth)" "$API/actions/runs/$1" \
        | tr ',' '\n' | grep -m1 "\"$2\"" | sed 's/.*: *"\{0,1\}\([^"]*\)"\{0,1\}/\1/'
}

show_status() {
    local id; id="$(run_id)"
    [ -z "$id" ] && { echo "Прогонов пока нет."; return 1; }
    echo "Прогон: https://github.com/$REPO/actions/runs/$id"
    echo "Статус: $(run_field "$id" status) / $(run_field "$id" conclusion)"
}

watch() {
    local id; id="$(run_id)"
    [ -z "$id" ] && { echo "Прогонов нет — сначала пуш."; exit 1; }
    echo "Прогон: https://github.com/$REPO/actions/runs/$id"
    local i=0
    while :; do
        local s c
        s="$(run_field "$id" status)"
        c="$(run_field "$id" conclusion)"
        printf '  [%ds] %s%s\n' "$((i * 15))" "$s" "${c:+ / $c}"
        [ "$s" = "completed" ] && break
        i=$((i + 1))
        [ "$i" -gt 80 ] && { echo "Слишком долго, смотри в браузере."; exit 1; }
        sleep 15
    done

    if [ "$c" != "success" ]; then
        echo "→ Сборка не прошла. Ошибки компиляции:"
        local tmp; tmp="$(mktemp -d)"
        curl -sL -H "$(auth)" -o "$tmp/run.zip" "$API/actions/runs/$id/logs"
        ( cd "$tmp" && unzip -q run.zip && grep -rh '\.kt:' . | sed 's/^[0-9T:.Z-]* //' | sort -u | head -30 )
        rm -rf "$tmp"
        exit 1
    fi
    echo "Готово."
}

download_apk() {
    local tmp; tmp="$(mktemp -d)"
    echo "→ Ищу артефакт"
    local url
    url="$(curl -s -H "$(auth)" "$API/actions/artifacts?per_page=10" \
        | tr '{' '\n' | grep -m1 'agy-ui-debug' \
        | tr ',' '\n' | grep -m1 '"archive_download_url"' | sed 's/.*"\(https[^"]*\)".*/\1/')"
    if [ -z "$url" ]; then
        echo "Артефакт не найден — сборка ещё идёт или упала."
        exit 1
    fi
    curl -sL -H "$(auth)" -o "$tmp/apk.zip" "$url"
    ( cd "$tmp" && unzip -q apk.zip )

    local out="$HOME/storage/downloads"
    [ -d "$out" ] || out="/sdcard/Download"
    mkdir -p "$out"
    local apk; apk="$(find "$tmp" -name '*.apk' | head -1)"
    [ -z "$apk" ] && { echo "APK в архиве не нашёлся."; exit 1; }
    cp "$apk" "$out/agy-ui-debug.apk"
    echo "APK: $out/agy-ui-debug.apk"
    command -v termux-open >/dev/null && termux-open "$out/agy-ui-debug.apk"
    rm -rf "$tmp"
}

case "${1:-all}" in
    push)   push_code ;;
    status) show_status ;;
    apk)    download_apk ;;
    all)    push_code; watch; download_apk ;;
    *)      echo "Использование: bash tools/ci.sh [push|status|apk]" ;;
esac
