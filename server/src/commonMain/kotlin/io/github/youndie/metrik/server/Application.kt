package io.github.youndie.metrik.server

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import io.github.smyrgeorge.sqlx4k.sqlite.sqlite
import io.github.youndie.metrik.agent.Metrik
import io.github.youndie.metrik.agent.MetrikCountersKey
import io.github.youndie.metrik.api.Api
import io.github.youndie.metrik.server.alert.AlertNotifier
import io.github.youndie.metrik.server.alert.AlertWorker
import io.github.youndie.metrik.server.alert.NoopNotifier
import io.github.youndie.metrik.server.alert.TelegramNotifier
import io.github.youndie.metrik.server.db.migrateDb
import io.github.youndie.metrik.server.ingest.AgentStats
import io.github.youndie.metrik.server.ingest.IngestService
import io.github.youndie.metrik.server.ingest.UdpReceiver
import io.github.youndie.metrik.server.mcp.ToolFacade
import io.github.youndie.metrik.server.mcp.installMcp
import io.github.youndie.metrik.server.query.AdminService
import io.github.youndie.metrik.server.query.QueryService
import io.github.youndie.metrik.server.query.adminRoutes
import io.github.youndie.metrik.server.query.alertRoutes
import io.github.youndie.metrik.server.query.alertTestRoute
import io.github.youndie.metrik.server.query.queryRoutes
import io.github.youndie.metrik.server.retention.RetentionWorker
import io.github.youndie.metrik.server.web.WebAssets
import io.github.youndie.metrik.server.web.webRoutes
import io.github.youndie.metrik.wire.MetrikJson
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.resources.Resources
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM

fun main() {
    val config = ServerConfig.fromEnv()
    val db = openDatabase(config.dbPath, config.dbMaxConnections)

    embeddedServer(CIO, port = config.httpPort, host = "0.0.0.0") {
        module(config, db)
    }.start(wait = true)
}

/**
 * Открывает базу и накатывает миграции **до** старта движка.
 *
 * `runBlocking` здесь осознан: сервер, поднявший порт раньше готовой схемы, отвечал бы ошибками
 * на первые запросы.
 */
fun openDatabase(
    path: String,
    maxConnections: Int = 2,
): ISQLite {
    val dbPath = path.toPath()
    val fileSystem = FileSystem.SYSTEM

    if (!fileSystem.exists(dbPath)) {
        dbPath.parent?.let { parent -> if (!fileSystem.exists(parent)) fileSystem.createDirectories(parent) }
        fileSystem.write(dbPath) { }
    }

    val db =
        sqlite(
            url = "sqlite://$path",
            options =
                ConnectionPool.Options
                    .builder()
                    .maxConnections(maxConnections)
                    .build(),
        )

    runBlocking { db.migrateDb() }

    return db
}

fun Application.module(
    config: ServerConfig,
    db: ISQLite,
) {
    val ingest = IngestService(db, config.ingestKey)
    val receiver = UdpReceiver(config.udpPort, ingest)
    val query = QueryService(db, minuteRetentionMs = config.retentionHours * 60 * 60 * 1000)
    val admin = AdminService(db)
    val notifier: AlertNotifier =
        if (config.telegramToken.isNullOrBlank() || config.telegramChatId.isNullOrBlank()) {
            // Без токена правила всё равно считаются и видны в UI, просто молча.
            NoopNotifier
        } else {
            TelegramNotifier(config.telegramToken, config.telegramChatId)
        }
    val alerts = AlertWorker(db, admin, notifier)

    val retention = RetentionWorker(db, minuteRetentionMs = config.retentionHours * 60 * 60 * 1000)

    alerts.start(this)
    retention.start(this)
    monitor.subscribe(ApplicationStopping) {
        alerts.stop()
        retention.stop()
    }

    receiver.start(this)
    monitor.subscribe(ApplicationStopping) { receiver.stop() }

    // Dogfooding: сервер мониторинга, которого не видно, — плохой сервер мониторинга.
    // Метрики уходят в собственный UDP-порт тем же агентом, что и у чужих сервисов.
    config.selfService?.let { name ->
        install(Metrik) {
            service = name
            apiKey = config.ingestKey
            endpoint = "127.0.0.1:${config.udpPort}"
        }
    }

    // Типизированные пути: контракт объявлен в :shared и общий с дашбордом.
    install(Resources)

    install(ContentNegotiation) { json(MetrikJson) }

    // Доступ для агентов. Без METRIK_MCP_TOKEN не ставится ничего: эндпоинт живёт в обход
    // oauth2-proxy, и «забыли задать токен» не может означать «выставили наружу».
    //
    // Строго после ContentNegotiation: `mcpStatelessStreamableHttp` ставит его сам, если тот ещё
    // не стоит, и приложение падало с DuplicatePluginException. Найдя плагин на месте, SDK
    // ограничивается предупреждением.
    installMcp(config, ToolFacade(query, alerts, admin))

    routing {
        // Дашборд отдаёт сам сервер — отдельного контейнера с nginx нет (M-98). Каталог
        // сканируется один раз на старте: файлы вшиты в образ и не меняются.
        // Пустая строка, а не только отсутствие переменной: образ всегда несёт статику и всегда
        // задаёт корень, так что выключить дашборд из чарта можно единственным способом — затерев
        // значение. `?.let` на пустой строке отдавал бы 404 на каждый файл вместо «дашборда нет».
        readEnv("METRIK_WEB_ROOT")?.takeIf { it.isNotBlank() }?.let { root -> webRoutes(WebAssets.scan(root)) }

        // Живость процесса и доступность базы: оркестратору нужно различать «поднялся» и «работает».
        get("/health") {
            db.fetchAll("SELECT 1;").getOrThrow()
            call.respondText("ok")
        }

        // Префикс /api несут сами ресурсы (см. `Api` в :shared), поэтому обёртки route("/api")
        // здесь нет: она бы задвоила путь.
        run {
            // Без этих счётчиков потери и отброшенные пакеты невидимы, а странные графики
            // нечем объяснить.
            get<Api.Self> {
                // Счётчики агента приезжают сюда только при самонаблюдении — иначе потери
                // на стороне отправителя не видны никому.
                val agentCounters = call.application.attributes.getOrNull(MetrikCountersKey)
                call.respond(
                    ingest.counters.snapshot().copy(
                        agent =
                            agentCounters?.let {
                                AgentStats(
                                    loops = it.loops,
                                    exited = it.exited,
                                    windows = it.windows,
                                    dropped = it.dropped,
                                    sendFailures = it.sendFailures,
                                    oversized = it.oversized,
                                )
                            },
                    ),
                )
            }

            alertRoutes(alerts)
            alertTestRoute(alerts, config)
            queryRoutes(query, config)
            adminRoutes(admin, config)
        }
    }
}
