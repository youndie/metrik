package ru.workinprogress.metrik.server

import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun main() {
    val config = ServerConfig.fromEnv()

    embeddedServer(CIO, port = config.httpPort, host = "0.0.0.0") {
        module(config)
    }.start(wait = true)
}

/**
 * Сборка приложения. Сейчас — только `/health`; приём метрик, хранение, чтение и алерты
 * приезжают в M3…M6, порядок и границы — в `BACKLOG.md`.
 */
fun Application.module(config: ServerConfig) {
    routing {
        // Живость процесса. Проверка БД добавится вместе с самой БД (M-31).
        get("/health") {
            call.respondText("ok")
        }
    }
}
