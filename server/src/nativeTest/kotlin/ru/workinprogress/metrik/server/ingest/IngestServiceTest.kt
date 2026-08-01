package ru.workinprogress.metrik.server.ingest

import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import ru.workinprogress.metrik.server.openDatabase
import ru.workinprogress.metrik.wire.Frame
import ru.workinprogress.metrik.wire.Histogram
import ru.workinprogress.metrik.wire.MetrikJson
import ru.workinprogress.metrik.wire.RouteSeries
import ru.workinprogress.metrik.wire.SlowSample
import ru.workinprogress.metrik.wire.SystemSnapshot
import ru.workinprogress.metrik.wire.encodeStatus
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val KEY = "test-ingest-key"
private const val WINDOW = 1_754_049_600_000L

class IngestServiceTest {
    private val dbPath = "/tmp/metrik-ingest-test-${randomSuffix()}.db"
    private val db: ISQLite = openDatabase(dbPath)
    private val now = WINDOW + 10_000
    private val ingest = IngestService(db, KEY, nowMs = { now })

    @AfterTest
    fun cleanup() {
        FileSystem.SYSTEM.delete(dbPath.toPath(), mustExist = false)
    }

    private fun frame(
        instance: String = "instance-a",
        windowStart: Long = WINDOW,
        packetIndex: Int = 0,
        packetCount: Int = 1,
        windowSeq: Long = 1,
        release: String? = null,
        routes: List<RouteSeries> = listOf(series()),
        system: SystemSnapshot? = null,
        slow: List<SlowSample>? = null,
    ) = Frame(
        apiKey = KEY,
        service = "orders-api",
        instance = instance,
        release = release,
        windowStart = windowStart,
        windowSeq = windowSeq,
        packetIndex = packetIndex,
        packetCount = packetCount,
        routes = routes,
        system = system,
        slow = slow,
    )

    private fun series(
        route: String = "/orders/{id}",
        status: Int = encodeStatus(200),
        count: Int = 10,
        sumMs: Long = 100,
        maxMs: Int = 40,
        durations: List<Long> = listOf(5, 40),
    ) = RouteSeries(
        method = "GET",
        route = route,
        status = status,
        count = count,
        sumMs = sumMs,
        maxMs = maxMs,
        buckets = Histogram().also { histogram -> durations.forEach(histogram::record) },
    )

    private suspend fun send(frame: Frame): IngestResult = ingest.accept(MetrikJson.encodeToString(frame))

    private suspend fun scalar(sql: String): Long =
        db
            .fetchAll(sql)
            .getOrThrow()
            .rows
            .first()
            .get(0)
            .asLong()

    @Test
    fun `an unknown service should be created by the first packet`() =
        runTest {
            // Given / When — регистрации сервисов нет by design.
            val result = send(frame())

            // Then
            assertEquals(IngestResult.ACCEPTED, result)
            assertEquals(1, scalar("SELECT COUNT(*) FROM services WHERE name = 'orders-api'"))
        }

    @Test
    fun `a packet with a foreign key should be dropped`() =
        runTest {
            // Given / When
            val result = ingest.accept(MetrikJson.encodeToString(frame().copy(apiKey = "not-our-key")))

            // Then
            assertEquals(IngestResult.BAD_KEY, result)
            assertEquals(0, scalar("SELECT COUNT(*) FROM services"))
        }

    @Test
    fun `malformed payloads should not break the receiver`() =
        runTest {
            // Given / When
            val result = ingest.accept("{not json at all")

            // Then
            assertEquals(IngestResult.MALFORMED, result)
            assertEquals(1, ingest.counters.snapshot().malformed)
        }

    @Test
    fun `a packet from the future should be rejected and the instance flagged`() =
        runTest {
            // Given — часы инстанса ушли на час вперёд.
            val result = send(frame(windowStart = now + 60 * 60 * 1000))

            // Then
            assertEquals(IngestResult.CLOCK_SKEW, result)
            assertEquals(0, scalar("SELECT COUNT(*) FROM route_windows"))
            assertEquals(1, scalar("SELECT COUNT(*) FROM instances WHERE clock_skew = 1"))
        }

    @Test
    fun `windows from different instances should merge into one row`() =
        runTest {
            // Given / When — два пода прислали одну и ту же минуту.
            send(frame(instance = "pod-a"))
            send(frame(instance = "pod-b"))

            // Then
            assertEquals(1, scalar("SELECT COUNT(*) FROM route_windows"))
            assertEquals(20, scalar("SELECT count FROM route_windows"))
            assertEquals(200, scalar("SELECT sum_ms FROM route_windows"))
        }

    @Test
    fun `histograms should merge bucket by bucket`() =
        runTest {
            // Given / When
            send(frame(instance = "pod-a"))
            send(frame(instance = "pod-b"))

            // Then — складывать перцентили нельзя, складываются бакеты.
            val stored =
                db
                    .fetchAll("SELECT buckets FROM route_windows")
                    .getOrThrow()
                    .rows
                    .first()
                    .get("buckets")
                    .asString()
            val histogram = Histogram.fromSparse(MetrikJson.decodeFromString(stored))
            assertEquals(4L, histogram.totalCount)
        }

    @Test
    fun `a redelivered packet should not double the numbers`() =
        runTest {
            // Given — слияние на записи это read-modify-write, поэтому дубль обязан отбрасываться.
            send(frame())

            // When
            val second = send(frame())

            // Then
            assertEquals(IngestResult.DUPLICATE, second)
            assertEquals(10, scalar("SELECT count FROM route_windows"))
        }

    @Test
    fun `packets of the same window should be distinguishable from duplicates`() =
        runTest {
            // Given / When — одно окно, два разных пакета.
            send(frame(packetIndex = 0, packetCount = 2, routes = listOf(series(route = "/a"))))
            val second = send(frame(packetIndex = 1, packetCount = 2, routes = listOf(series(route = "/b"))))

            // Then
            assertEquals(IngestResult.ACCEPTED, second)
            assertEquals(2, scalar("SELECT COUNT(*) FROM route_windows"))
        }

    @Test
    fun `a gap in window numbers should be counted`() =
        runTest {
            // Given
            send(frame(windowSeq = 1))

            // When — окна 2 и 3 не долетели.
            send(frame(windowStart = WINDOW + 60_000, windowSeq = 4))

            // Then
            assertEquals(2, ingest.counters.snapshot().missedWindows)
        }

    @Test
    fun `a release change should leave a deploy marker`() =
        runTest {
            // Given
            send(frame(release = "1.0.0"))

            // When
            send(frame(windowStart = WINDOW + 60_000, windowSeq = 2, release = "1.0.1"))

            // Then — «стало хуже» без «после чего» бесполезно.
            assertEquals(2, scalar("SELECT COUNT(*) FROM deploys"))
            assertEquals(1, scalar("SELECT COUNT(*) FROM deploys WHERE release = '1.0.1'"))
        }

    @Test
    fun `an unchanged release should not repeat the deploy marker`() =
        runTest {
            // Given / When
            send(frame(release = "1.0.0"))
            send(frame(windowStart = WINDOW + 60_000, windowSeq = 2, release = "1.0.0"))

            // Then
            assertEquals(1, scalar("SELECT COUNT(*) FROM deploys"))
        }

    @Test
    fun `system snapshots and slow samples should be stored`() =
        runTest {
            // Given
            val snapshot =
                SystemSnapshot(
                    heapUsedBytes = 1_000,
                    heapMaxBytes = 4_000,
                    cpuPermille = 250,
                    threads = 12,
                    uptimeSeconds = 60,
                )
            val slow = listOf(SlowSample("POST", "/orders", encodeStatus(503), 8_000, WINDOW + 100))

            // When
            send(frame(system = snapshot, slow = slow))

            // Then
            assertEquals(1, scalar("SELECT COUNT(*) FROM system_windows"))
            assertEquals(1, scalar("SELECT COUNT(*) FROM slow_samples WHERE status = 503"))
        }

    @Test
    fun `counters should reflect what happened`() =
        runTest {
            // Given / When
            send(frame())
            send(frame())
            ingest.accept("garbage")

            // Then
            val stats = ingest.counters.snapshot()
            assertEquals(1, stats.accepted)
            assertEquals(1, stats.duplicate)
            assertEquals(1, stats.malformed)
            assertTrue(stats.badKey == 0)
        }
}

private var counter = 0

private fun randomSuffix(): String = "${counter++}-${WINDOW % 100_000}"
