# Нативный бинарь: ни JVM, ни рантайма в образе. Собирается снаружи (CI на linux-раннере),
# сюда только копируется — сборка Kotlin/Native внутри docker заняла бы десятки минут.
FROM debian:bookworm-slim

RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates \
 && rm -rf /var/lib/apt/lists/*

COPY server/build/bin/linuxX64/releaseExecutable/server.kexe /usr/local/bin/metrik

# База — один файл; каталог обязан быть персистентным томом.
VOLUME ["/data"]
ENV METRIK_DB_PATH=/data/metrik.db

EXPOSE 8080
EXPOSE 9999/udp

ENTRYPOINT ["/usr/local/bin/metrik"]
