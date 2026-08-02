package ru.workinprogress.metrik.agent

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import ru.workinprogress.metrik.wire.Frame
import ru.workinprogress.metrik.wire.MetrikJson
import ru.workinprogress.metrik.wire.RouteSeries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class RecordingSender(
    private val failEveryTime: Boolean = false,
) : MetrikSender {
    val packets = mutableListOf<String>()

    override suspend fun send(packet: String) {
        if (failEveryTime) throw IllegalStateException("metrik-server is unreachable")
        packets += packet
    }

    override fun close() = Unit
}

class MetrikPluginTest {
    private fun frames(sender: RecordingSender): List<Frame> = sender.packets.map { MetrikJson.decodeFromString<Frame>(it) }

    /**
     * Ждёт, пока в отправленных окнах наберётся нужное число запросов по [route].
     *
     * Считать по всем окнам, а не по первому пришедшему, обязательно: окно в тесте короткое, и на
     * загруженной машине запросы легко расходятся по двум окнам. Проверять надо инвариант «одна
     * серия на шаблон, столько-то запросов всего», а не удачное попадание в одно окно.
     */
    private suspend fun awaitRouteCount(
        sender: RecordingSender,
        route: String,
        expected: Int,
    ): List<RouteSeries> {
        withTimeout(20_000) {
            while (frames(sender).flatMap { it.routes }.filter { it.route == route }.sumOf { it.count } < expected) {
                delay(10)
            }
        }
        return frames(sender).flatMap { it.routes }.filter { it.route == route }
    }

    private fun ApplicationTestBuilder.installMetrik(sender: MetrikSender) {
        application {
            // Явный receiver: у ApplicationTestBuilder тоже есть install, и без this он неоднозначен.
            this.install(Metrik) {
                service = "test-service"
                apiKey = "test-key"
                endpoint = "127.0.0.1:19999"
                instanceId = "test-instance"
                windowMs = 150
                systemMetrics = false
                senderFactory = { sender }
            }
            routing {
                get("/users/{id}") { call.respondText("ok") }
                get("/boom") { throw IllegalStateException("boom") }
            }
        }
    }

    @Test
    fun `the series label should be the route template and not the request path`() =
        testApplication {
            // Given
            val sender = RecordingSender()
            installMetrik(sender)

            // When — три разных id.
            client.get("/users/1")
            client.get("/users/2")
            client.get("/users/3")

            // Then — все три запроса попали в один шаблон, а не в три разные серии.
            val series = awaitRouteCount(sender, "/users/{id}", expected = 3)
            assertEquals(setOf("/users/{id}"), series.map { it.route }.toSet())
            assertEquals(setOf(2), series.map { it.status }.toSet())
            assertEquals(3, series.sumOf { it.count })
            assertTrue(
                frames(sender).flatMap { it.routes }.none { it.route == "/users/1" },
                "сырой путь просочился в серию",
            )
        }

    @Test
    fun `unmatched requests should share one series`() =
        testApplication {
            // Given
            val sender = RecordingSender()
            installMetrik(sender)

            // When
            client.get("/nope/1")
            client.get("/nope/2")

            // Then
            val unmatched = awaitRouteCount(sender, ROUTE_UNMATCHED, expected = 2)
            assertEquals(setOf(ROUTE_UNMATCHED), unmatched.map { it.route }.toSet())
            assertEquals(2, unmatched.sumOf { it.count })
        }

    @Test
    fun `a failing sender should not affect the host service`() =
        testApplication {
            // Given — metrik-server недоступен, отправка падает на каждом окне.
            installMetrik(RecordingSender(failEveryTime = true))

            // When
            val response = client.get("/users/7")
            delay(300)
            val afterFlush = client.get("/users/7")

            // Then
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("ok", response.bodyAsText())
            assertEquals(HttpStatusCode.OK, afterFlush.status)
        }

    @Test
    fun `frames should carry the window identity needed to detect losses`() =
        testApplication {
            // Given
            val sender = RecordingSender()
            installMetrik(sender)

            // When
            client.get("/users/1")

            // Then
            val frame = awaitRouteCount(sender, "/users/{id}", expected = 1).let { frames(sender).first() }
            assertEquals("test-service", frame.service)
            assertEquals("test-instance", frame.instance)
            assertEquals(150, frame.windowMs)
            assertEquals(0, frame.packetIndex)
            assertEquals(1, frame.packetCount)
            assertTrue(frame.windowStart % 150 == 0L, "window start is not aligned: ${frame.windowStart}")
        }
}
