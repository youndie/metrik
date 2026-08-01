package ru.workinprogress.metrik.server.ingest

import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import ru.workinprogress.metrik.server.openDatabase
import ru.workinprogress.metrik.wire.Frame
import ru.workinprogress.metrik.wire.Histogram
import ru.workinprogress.metrik.wire.MetrikJson
import ru.workinprogress.metrik.wire.RouteSeries
import ru.workinprogress.metrik.wire.encodeStatus
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTime

private const val KEY = "udp-test-key"
private const val WINDOW = 1_754_049_600_000L
private const val PORT = 19_313

class UdpReceiverTest {
    private val dbPath = "/tmp/metrik-udp-test.db"
    private val db = openDatabase(dbPath)
    private val ingest = IngestService(db, KEY, nowMs = { WINDOW + 1_000 })

    @AfterTest
    fun cleanup() {
        FileSystem.SYSTEM.delete(dbPath.toPath(), mustExist = false)
    }

    private fun frame(
        service: String,
        instance: String,
        windowSeq: Long = 1,
    ) = Frame(
        apiKey = KEY,
        service = service,
        instance = instance,
        windowStart = WINDOW,
        windowSeq = windowSeq,
        packetIndex = 0,
        packetCount = 1,
        routes =
            listOf(
                RouteSeries(
                    method = "GET",
                    route = "/orders/{id}",
                    status = encodeStatus(200),
                    count = 10,
                    sumMs = 100,
                    maxMs = 40,
                    buckets = Histogram.of(5, 40),
                ),
            ),
    )

    private suspend fun scalar(sql: String): Long =
        db
            .fetchAll(sql)
            .getOrThrow()
            .rows
            .first()
            .get(0)
            .asLong()

    @Test
    fun `a datagram sent over the wire should reach storage`() =
        runTest {
            // Given
            val scope = CoroutineScope(coroutineContext + Job())
            val receiver = UdpReceiver(PORT, ingest, host = "127.0.0.1")
            receiver.start(scope)
            delay(200)

            // When
            val selector = SelectorManager()
            val socket = aSocket(selector).udp().connect(InetSocketAddress("127.0.0.1", PORT))
            val payload = MetrikJson.encodeToString(frame("wire-service", "pod-a")).encodeToByteArray()
            socket.send(Datagram(Buffer().also { it.write(payload) }, socket.remoteAddress))

            // Then
            withTimeout(10_000) {
                while (ingest.counters.snapshot().accepted == 0) delay(20)
            }
            assertEquals(1, scalar("SELECT COUNT(*) FROM services WHERE name = 'wire-service'"))

            socket.close()
            selector.close()
            receiver.stop()
        }

    @Test
    fun `ingesting many windows should stay fast enough for a minute of traffic`() =
        runTest {
            // Given — 50 сервисов по 10 инстансов: столько окон приходит раз в минуту.
            val frames =
                (1..50)
                    .flatMap { service ->
                        (1..10).map { instance -> frame("service-$service", "pod-$instance") }
                    }.map { MetrikJson.encodeToString(it) }

            // When
            val elapsed = measureTime { frames.forEach { payload -> ingest.accept(payload) } }

            // Then — окно минутное, так что даже секунда на весь приём это огромный запас.
            println("ingest of ${frames.size} frames: $elapsed (${elapsed / frames.size} per frame)")
            assertTrue(elapsed.inWholeSeconds < 20, "ingest is too slow: $elapsed")
            assertEquals(50, scalar("SELECT COUNT(*) FROM services"))
            assertEquals(50, scalar("SELECT COUNT(*) FROM route_windows"))
            assertEquals(100, scalar("SELECT count FROM route_windows LIMIT 1"))
        }
}
