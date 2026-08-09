# Нативный бинарь: ни JVM, ни рантайма в образе. Собирается снаружи (CI на linux-раннере),
# сюда только копируется — сборка Kotlin/Native внутри docker заняла бы десятки минут.
FROM debian:bookworm-slim AS web

RUN apt-get update \
 && apt-get install -y --no-install-recommends gzip \
 && rm -rf /var/lib/apt/lists/*

COPY composeApp/build/dist/wasmJs/productionExecutable/ /web/

# Сжимаем один раз здесь, а не на каждый запрос: плагина компрессии для Kotlin/Native не
# существует (research §1.8), поэтому сервер отдаёт готовый .gz рядом с файлом.
RUN find /web -type f \( -name '*.js' -o -name '*.wasm' -o -name '*.html' -o -name '*.css' \
      -o -name '*.json' -o -name '*.svg' \) -exec gzip -k9 {} +

FROM debian:bookworm-slim

RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates \
 && rm -rf /var/lib/apt/lists/*

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
