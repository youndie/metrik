package ru.workinprogress.metrik.server

import ru.workinprogress.metrik.wire.DEFAULT_INGEST_PORT

/**
 * Конфигурация сервера. Полный список переменных — `docs/services/metrik-server.md`.
 */
class ServerConfig(
    val httpPort: Int,
    val udpPort: Int,
    val dbPath: String,
    /**
     * Размер пула соединений к SQLite.
     *
     * Двойка вместо десятки не экономия ради экономии: замер на стенде (пять парных повторов,
     * одинаковая нагрузка) дал −59 МиБ на полке потребления и при этом +10% запросов в секунду.
     * Писатель в SQLite всё равно один, и лишние соединения только делят между собой тот же лок,
     * а платит за каждое из них процесс — отдельным потоком, кэшем страниц и кэшем выражений.
     * Десятка была не выбором, а дефолтом `sqlx`.
     */
    val dbMaxConnections: Int = 2,
    val ingestKey: String,
    /** Пусто — админ любой прошедший прокси: инсталляция принадлежит одной команде. */
    val admins: Set<String> = emptySet(),
    val retentionHours: Long = 48,
    val telegramToken: String? = null,
    val telegramChatId: String? = null,
    /** Имя, под которым metrik-server мониторит сам себя. `null` — самонаблюдение выключено. */
    val selfService: String? = null,
    /**
     * Токен MCP-эндпоинта. `null` — эндпоинта нет вовсе.
     *
     * Отсутствие настройки обязано означать закрытое состояние, а не открытое: endpoint живёт в
     * обход oauth2-proxy, и «забыли задать токен» не может превращаться в «выставили наружу».
     */
    val mcpToken: String? = null,
    /**
     * Хосты, с которых принимается MCP-запрос. Пусто — проверка выключена.
     *
     * Защита от DNS rebinding: браузер на машине жертвы резолвит свой домен в наш адрес и ходит
     * к endpoint'у. Локально этот класс ошибок не воспроизводится — там хост всегда localhost.
     */
    val mcpAllowedHosts: List<String> = emptyList(),
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
                dbMaxConnections = readEnv("METRIK_DB_MAX_CONNECTIONS")?.toIntOrNull() ?: 2,
                ingestKey = ingestKey,
                admins =
                    readEnv("METRIK_ADMINS")
                        .orEmpty()
                        .split(',')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .toSet(),
                retentionHours = readEnv("METRIK_RETENTION_HOURS")?.toLongOrNull() ?: 48,
                telegramToken = readEnv("METRIK_TELEGRAM_TOKEN"),
                telegramChatId = readEnv("METRIK_TELEGRAM_CHAT_ID"),
                selfService = readEnv("METRIK_SELF_SERVICE"),
                mcpToken = readEnv("METRIK_MCP_TOKEN")?.takeIf { it.isNotBlank() },
                mcpAllowedHosts =
                    readEnv("METRIK_MCP_ALLOWED_HOSTS")
                        .orEmpty()
                        .split(',')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() },
            )
        }
    }
}

/**
 * Чтение переменной окружения. В `commonMain` нет `java.*`, а `System.getenv` есть только на JVM —
 * отсюда expect/actual.
 */
expect fun readEnv(name: String): String?
