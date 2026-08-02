package ru.workinprogress.metrik.server

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import io.github.smyrgeorge.sqlx4k.sqlite.sqlite
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import ru.workinprogress.metrik.agent.Metrik
import ru.workinprogress.metrik.agent.MetrikCountersKey
import ru.workinprogress.metrik.server.alert.AlertNotifier
import ru.workinprogress.metrik.server.alert.AlertWorker
import ru.workinprogress.metrik.server.alert.NoopNotifier
import ru.workinprogress.metrik.server.alert.TelegramNotifier
import ru.workinprogress.metrik.server.db.migrateDb
import ru.workinprogress.metrik.server.ingest.AgentStats
import ru.workinprogress.metrik.server.ingest.IngestService
import ru.workinprogress.metrik.server.ingest.UdpReceiver
import ru.workinprogress.metrik.server.query.AdminService
import ru.workinprogress.metrik.server.query.QueryService
import ru.workinprogress.metrik.server.query.adminRoutes
import ru.workinprogress.metrik.server.query.alertRoutes
import ru.workinprogress.metrik.server.query.queryRoutes
import ru.workinprogress.metrik.server.retention.RetentionWorker
import ru.workinprogress.metrik.wire.MetrikJson

fun main() {
    val config = ServerConfig.fromEnv()
    val db = openDatabase(config.dbPath)

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
fun openDatabase(path: String): ISQLite {
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
                    .maxConnections(10)
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

    install(ContentNegotiation) { json(MetrikJson) }

    routing {
        // Живость процесса и доступность базы: оркестратору нужно различать «поднялся» и «работает».
        get("/health") {
            db.fetchAll("SELECT 1;").getOrThrow()
            call.respondText("ok")
        }

        route("/api") {
            // Без этих счётчиков потери и отброшенные пакеты невидимы, а странные графики
            // нечем объяснить.
            get("/self") {
                // Счётчики агента приезжают сюда только при самонаблюдении — иначе потери
                // на стороне отправителя не видны никому.
                val agentCounters = call.application.attributes.getOrNull(MetrikCountersKey)
                call.respond(
                    ingest.counters.snapshot().copy(
                        agent =
                            agentCounters?.let {
                                AgentStats(
                                    loops = it.loops,
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
            queryRoutes(query, config)
            adminRoutes(admin, config)
        }
    }
}
