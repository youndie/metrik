package ru.workinprogress.metrik.server.retention

import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import ru.workinprogress.metrik.server.ingest.IngestService
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

private const val KEY = "retention-test-key"
private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE

class RetentionWorkerTest {
    private val dbPath = "/tmp/metrik-retention-test.db"
    private val db = openDatabase(dbPath)
    private var clock = 1_754_049_600_000L

    @AfterTest
    fun cleanup() {
        FileSystem.SYSTEM.delete(dbPath.toPath(), mustExist = false)
    }

    private suspend fun ingest(
        windowStart: Long,
        windowSeq: Long,
        count: Int = 10,
    ) {
        IngestService(db, KEY, nowMs = { windowStart + 1_000 }).accept(
            MetrikJson.encodeToString(
                Frame(
                    apiKey = KEY,
                    service = "orders-api",
                    instance = "pod-a",
                    windowStart = windowStart,
                    windowSeq = windowSeq,
                    packetIndex = 0,
                    packetCount = 1,
                    routes =
                        listOf(
                            RouteSeries(
                                method = "GET",
                                route = "/orders/{id}",
                                status = encodeStatus(200),
                                count = count,
                                sumMs = count * 10L,
                                maxMs = 10,
                                buckets = Histogram().also { h -> repeat(count) { h.record(10) } },
                            ),
                        ),
                ),
            ),
        )
    }

    private suspend fun scalar(sql: String): Long =
        db
            .fetchAll(sql)
            .getOrThrow()
            .rows
            .first()
            .get(0)
            .asLong()

    private fun worker() = RetentionWorker(db, minuteRetentionMs = 2 * HOUR, nowMs = { clock })

    @Test
    fun `minute windows should roll up into hours`() =
        runTest {
            // Given — три минуты одного часа.
            val base = clock
            ingest(base, 1)
            ingest(base + MINUTE, 2)
            ingest(base + 2 * MINUTE, 3)

            // When — час прошёл, окна давно закрыты.
            clock = base + 2 * HOUR
            worker().tick()

            // Then
            assertEquals(1, scalar("SELECT COUNT(*) FROM route_rollups WHERE granularity = 'hour'"))
            assertEquals(30, scalar("SELECT count FROM route_rollups WHERE granularity = 'hour'"))
        }

    @Test
    fun `fresh windows should not be rolled up`() =
        runTest {
            // Given — окно закрылось только что, в него ещё могут долетать пакеты отстающих инстансов.
            val base = clock
            ingest(base, 1)

            // When
            clock = base + MINUTE
            worker().tick()

            // Then
            assertEquals(0, scalar("SELECT COUNT(*) FROM route_rollups"))
        }

    @Test
    fun `minute windows beyond retention should be deleted`() =
        runTest {
            // Given
            val base = clock
            ingest(base, 1)

            // When
            clock = base + 3 * HOUR
            worker().tick()

            // Then — минутки ушли, но час остался.
            assertEquals(0, scalar("SELECT COUNT(*) FROM route_windows"))
            assertTrue(scalar("SELECT COUNT(*) FROM route_rollups") > 0)
        }

    @Test
    fun `stale instances should be removed`() =
        runTest {
            // Given — под пересоздался при выкате и больше не присылает.
            val base = clock
            ingest(base, 1)

            // When
            clock = base + 3 * 24 * HOUR
            worker().tick()

            // Then
            assertEquals(0, scalar("SELECT COUNT(*) FROM instances"))
        }

    @Test
    fun `receipts should not outlive the windows they describe`() =
        runTest {
            // Given
            val base = clock
            ingest(base, 1)
            assertTrue(scalar("SELECT COUNT(*) FROM window_receipts") > 0)

            // When
            clock = base + 3 * HOUR
            worker().tick()

            // Then
            assertEquals(0, scalar("SELECT COUNT(*) FROM window_receipts"))
        }
}
