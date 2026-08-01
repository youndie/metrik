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
    private suspend fun awaitFrames(sender: RecordingSender): List<Frame> {
        withTimeout(10_000) {
            while (sender.packets.isEmpty()) delay(10)
        }
        return sender.packets.map { MetrikJson.decodeFromString<Frame>(it) }
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

            // Then — одна серия, а не три.
            val series = awaitFrames(sender).flatMap { it.routes }.filter { it.route != ROUTE_UNMATCHED }
            assertEquals(1, series.size, "expected a single series, got ${series.map { it.route }}")
            assertEquals("/users/{id}", series.single().route)
            assertEquals(3, series.single().count)
            assertEquals(2, series.single().status)
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
            val unmatched = awaitFrames(sender).flatMap { it.routes }.filter { it.route == ROUTE_UNMATCHED }
            assertEquals(1, unmatched.size)
            assertEquals(2, unmatched.single().count)
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
            val frame = awaitFrames(sender).first()
            assertEquals("test-service", frame.service)
            assertEquals("test-instance", frame.instance)
            assertEquals(150, frame.windowMs)
            assertEquals(0, frame.packetIndex)
            assertEquals(1, frame.packetCount)
            assertTrue(frame.windowStart % 150 == 0L, "window start is not aligned: ${frame.windowStart}")
        }
}
