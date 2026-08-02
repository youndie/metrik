package ru.workinprogress.metrik.sample

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import ru.workinprogress.metrik.agent.Metrik
import ru.workinprogress.metrik.agent.MetrikCountersKey

/**
 * Минимальный JVM-сервис с агентом — стенд для отладки.
 *
 * Нужен потому, что в кластере агент ведёт себя иначе, чем в тестах, а тесты подменяют отправку
 * фейком. Здесь всё настоящее: реальный UDP-сокет, реальные окна, счётчики наружу.
 */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8081

    embeddedServer(CIO, port = port, host = "0.0.0.0") {
        module()
    }.start(wait = true)
}

fun Application.module() {
    install(Metrik) {
        service = System.getenv("METRIK_SERVICE") ?: "sample-jvm"
        apiKey = System.getenv("METRIK_KEY") ?: "dev-key"
        endpoint = System.getenv("METRIK_ENDPOINT") ?: "127.0.0.1:9999"
        windowMs = System.getenv("METRIK_WINDOW_MS")?.toLongOrNull() ?: 60_000
        instanceId = "sample-1"
    }

    routing {
        get("/ping") { call.respondText("pong") }
        get("/users/{id}") { call.respondText("user ${call.parameters["id"]}") }
        get("/self") {
            val c = call.application.attributes[MetrikCountersKey]
            call.respondText(
                "loops=${c.loops} exited=${c.exited} windows=${c.windows} " +
                    "dropped=${c.dropped} sendFailures=${c.sendFailures} oversized=${c.oversized}",
            )
        }
    }
}
