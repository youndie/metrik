# Нативный бинарь: ни JVM, ни рантайма в образе. Собирается снаружи (CI на linux-раннере),
# сюда только копируется — сборка Kotlin/Native внутри docker стоила бы времени на каждый образ.
FROM debian:trixie-slim AS web

RUN apt-get update \
 && apt-get install -y --no-install-recommends gzip \
 && rm -rf /var/lib/apt/lists/*

COPY composeApp/build/dist/wasmJs/productionExecutable/ /web/

# Сжимаем один раз здесь, а не на каждый запрос: плагина компрессии для Kotlin/Native не
# существует (research §1.8), поэтому сервер отдаёт готовый .gz рядом с файлом.
RUN find /web -type f \( -name '*.js' -o -name '*.wasm' -o -name '*.html' -o -name '*.css' \
      -o -name '*.json' -o -name '*.svg' \) -exec gzip -k9 {} +

# Рантайм — distroless вместо `debian:bookworm-slim`: 10 683 942 байта базы вместо ~75 МБ, и в
# образе не остаётся ни оболочки, ни пакетного менеджера. Разбор — youndie/metrik#36.
#
# **Проверено `readelf -d` на настоящем бинаре, а не по списку из соседнего проекта:**
#
#     libm.so.6  libpthread.so.0  librt.so.1  libz.so.1  libdl.so.2  libgcc_s.so.1  libc.so.6
#
# Всё это в `distroless/cc-debian13` есть — **включая `libz.so.1`**, и вот это единственное, что
# отличает выбор версии от вкусовщины. В `distroless/cc-debian12` zlib нет, и образ на ней
# потребовал бы копии `libz.so.1` из донорского `debian:bookworm-slim` — а копия системной
# библиотеки связывает два образа по glibc: донор обязан быть не новее рантайма, расхождение
# собирается молча и падает при exec с `GLIBC_2.xx not found`. Здесь этой связки нет вовсе,
# потому что нет копии. Появится новая зависимость — гнать `readelf -d` заново, а не гадать.
#
# **Чего проверять НЕ надо, вопреки первому впечатлению, — glibc раннера.** Kotlin/Native линкует
# против собственного sysroot'а, а не против системного: `readelf -V` на бинаре показывает
# требования не выше `GLIBC_2.18`, хотя собран он на Ubuntu 24.04 с glibc 2.39. Правило
# «рантайм не старше сборочной машины» относится к скопированным файлам, а не к самому бинарю.
#
# `ca-certificates` больше не ставятся: в distroless/cc они уже есть
# (`/etc/ssl/certs/ca-certificates.crt`, проверено распаковкой образа). OpenSSL внутри
# `ktor-client-curl` читает именно этот путь — без него доставка алертов в Telegram молча ломается
# на рукопожатии (research §1.7).
FROM gcr.io/distroless/cc-debian13

COPY server/build/bin/linuxX64/releaseExecutable/server.kexe /usr/local/bin/metrik
COPY --from=web /web /usr/share/metrik/web

# База — один файл; каталог обязан быть персистентным томом.
VOLUME ["/data"]
ENV METRIK_DB_PATH=/data/metrik.db
# Дашборд отдаёт сам сервер: отдельного контейнера с nginx больше нет.
ENV METRIK_WEB_ROOT=/usr/share/metrik/web

EXPOSE 8080
EXPOSE 9999/udp

ENTRYPOINT ["/usr/local/bin/metrik"]
