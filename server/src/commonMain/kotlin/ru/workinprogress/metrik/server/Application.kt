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
import ru.workinprogress.metrik.server.db.migrateDb
import ru.workinprogress.metrik.server.ingest.IngestService
import ru.workinprogress.metrik.server.ingest.UdpReceiver
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

    receiver.start(this)
    monitor.subscribe(ApplicationStopping) { receiver.stop() }

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
            get("/self") { call.respond(ingest.counters.snapshot()) }
        }
    }
}
