package ru.workinprogress.metrik.server

import ru.workinprogress.metrik.wire.DEFAULT_INGEST_PORT

/**
 * Конфигурация сервера. Полный список переменных — `docs/services/metrik-server.md`.
 */
class ServerConfig(
    val httpPort: Int,
    val udpPort: Int,
    val dbPath: String,
    val ingestKey: String,
) {
    companion object {
        fun fromEnv(): ServerConfig {
            val ingestKey = readEnv("METRIK_INGEST_KEY").orEmpty()

            // Ingest-key один на инсталляцию и обязателен: без него любой, кто дотянулся до
            // UDP-порта, пишет метрики в чужую базу.
            require(ingestKey.isNotBlank()) { "METRIK_INGEST_KEY is required" }

            return ServerConfig(
                httpPort = readEnv("METRIK_HTTP_PORT")?.toIntOrNull() ?: 8080,
                udpPort = readEnv("METRIK_UDP_PORT")?.toIntOrNull() ?: DEFAULT_INGEST_PORT,
                dbPath = readEnv("METRIK_DB_PATH") ?: "/data/metrik.db",
                ingestKey = ingestKey,
            )
        }
    }
}

/**
 * Чтение переменной окружения. В `commonMain` нет `java.*`, а `System.getenv` есть только на JVM —
 * отсюда expect/actual.
 */
expect fun readEnv(name: String): String?
