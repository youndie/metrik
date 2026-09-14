#!/usr/bin/env bash
# Гоняет собранный образ metrik по тем путям, которые ломаются от смены базы, и только от неё.
#
# **Раньше образ не запускал никто.** `publish.yml` собирал его и пушил; всё, что проверялось, —
# что docker build не упал. А ломается здесь не сборка: в рантайме нет ни оболочки, ни
# пакетного менеджера, и недостающая разделяемая библиотека (`readelf -d` называет семь) видна
# только при старте процесса. Отсутствующий `libz.so.1` — это `docker build` зелёный и под,
# который не поднимается.
#
# Поэтому проверки идут до настоящей работы, а не до кода ответа:
#
#   * три пробы и `/version` — процесс жив, база открыта, миграции прошли, а `version:` значит,
#     что сгенерированный плагином объект действительно скомпилирован внутрь бинаря;
#   * оболочка дашборда и предсжатый `.gz` рядом с бандлом — то, что кладёт первая стадия
#     Dockerfile'а, и единственное доказательство, что она отработала;
#   * датаграмма приёма → SQLite → `/api` — весь тракт сервиса за две секунды, без ожидания
#     минутного окна.
#
# Использование: dev/image-smoke.sh [http-url] [udp-порт]
#                по умолчанию http://localhost:8080 и 9999
#                METRIK_INGEST_KEY — ключ, с которым запущен контейнер (по умолчанию `smoke-key`)
#
# Ждёт ПУСТУЮ базу: сервис `smoke-service` создаётся приёмом и проверяется по имени.
set -euo pipefail

base=${1:-http://localhost:8080}
udp_port=${2:-9999}
key=${METRIK_INGEST_KEY:-smoke-key}

# Дашборд авторизуется заголовками reverse proxy — сервер их требует и в контейнере тоже.
proxy=(-H 'X-Auth-Request-User: smoke' -H 'X-Auth-Request-Email: smoke@example.test')

fail() {
    printf 'smoke: %s\n' "$1" >&2
    exit 1
}

printf 'smoke: ждём %s\n' "$base" >&2
for _ in $(seq 1 60); do
    if curl -fsS -o /dev/null "$base/health/startup" 2>/dev/null; then break; fi
    sleep 1
done

for probe in /health/startup /health/ready /health/live; do
    curl -fsS -o /dev/null "$base$probe" || fail "$probe не ответил 200"
done
curl -fsS "$base/version" | grep -q '^version: ' || fail "/version не назвал версию"

# Оболочка дашборда. Её нет в дистрибутиве Compose — она лежит в ресурсах модуля и попадает в
# бандл; проверка заодно ловит образ, собранный без стадии `web`.
curl -fsS "$base/" | grep -q 'composeApp.js' || fail "по / не отдалась оболочка дашборда"

# Предсжатый двойник. Сжатие делается один раз в сборке образа (плагина компрессии для
# Kotlin/Native нет), поэтому `Content-Encoding: gzip` здесь — про содержимое образа, а не про
# поведение сервера.
encoding=$(curl -fsS -H 'Accept-Encoding: gzip' -o /dev/null -D - "$base/composeApp.js" |
    tr -d '\r' | grep -i '^content-encoding:' || true)
case "$encoding" in
    *gzip*) ;;
    '') fail "composeApp.js отдался без Content-Encoding — .gz рядом с файлом не доехал" ;;
    *) fail "composeApp.js отдался с $encoding" ;;
esac

# Приём. Датаграмма самодостаточна (protocol-ingest), поэтому одной хватает: начало окна кратно
# `d`, один маршрут, три запроса.
host=${base#*://}
host=${host%%:*}
now=$(( ($(date +%s) / 60) * 60000 ))
frame="{\"v\":1,\"k\":\"$key\",\"s\":\"smoke-service\",\"i\":\"smoke-1\",\"t\":$now,\"d\":60000,"
frame="$frame\"w\":0,\"q\":0,\"n\":1,\"r\":[{\"m\":\"GET\",\"p\":\"/smoke\",\"c\":2,\"n\":3,\"s\":30,\"x\":15,\"b\":[[10,3]]}]}"
printf '%s' "$frame" > "/dev/udp/$host/$udp_port" ||
    fail "датаграмма не ушла на $host:$udp_port"

# Приём асинхронный и ответа не даёт — по протоколу. Значит опрашиваем.
for _ in $(seq 1 15); do
    services=$(curl -fsS "${proxy[@]}" "$base/api/services" 2>/dev/null || true)
    if printf '%s' "$services" | grep -q '"name":"smoke-service"'; then
        printf 'smoke: датаграмма доехала до /api/services\n' >&2
        exit 0
    fi
    sleep 1
done

fail "сервис не появился в /api/services — тракт приём → SQLite → чтение оборван: ${services:-пусто}"
