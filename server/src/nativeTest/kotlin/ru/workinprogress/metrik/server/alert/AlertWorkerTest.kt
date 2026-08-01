package ru.workinprogress.metrik.server.alert

import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import ru.workinprogress.metrik.api.AlertRuleView
import ru.workinprogress.metrik.server.ingest.IngestService
import ru.workinprogress.metrik.server.openDatabase
import ru.workinprogress.metrik.server.query.AdminService
import ru.workinprogress.metrik.wire.Frame
import ru.workinprogress.metrik.wire.Histogram
import ru.workinprogress.metrik.wire.MetrikJson
import ru.workinprogress.metrik.wire.RouteSeries
import ru.workinprogress.metrik.wire.encodeStatus
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val KEY = "alert-test-key"
private const val WINDOW = 1_754_049_600_000L
private const val MINUTE = 60_000L

private class RecordingNotifier : AlertNotifier {
    val messages = mutableListOf<String>()
    var failing = false

    override suspend fun notify(
        text: String,
        chatId: String?,
    ) {
        if (failing) throw IllegalStateException("telegram is down")
        messages += text
    }
}

class AlertWorkerTest {
    private val dbPath = "/tmp/metrik-alert-test.db"
    private val db = openDatabase(dbPath)
    private val admin = AdminService(db)
    private val notifier = RecordingNotifier()
    private var clock = WINDOW + MINUTE

    private fun worker() = AlertWorker(db, admin, notifier, nowMs = { clock })

    @AfterTest
    fun cleanup() {
        FileSystem.SYSTEM.delete(dbPath.toPath(), mustExist = false)
    }

    private suspend fun ingest(
        okCount: Int,
        errorCount: Int,
        windowStart: Long = WINDOW,
        windowSeq: Long = 1,
        service: String = "orders-api",
    ) {
        val routes =
            listOfNotNull(
                okCount.takeIf { it > 0 }?.let { series(encodeStatus(200), it, 5) },
                errorCount.takeIf { it > 0 }?.let { series(encodeStatus(503), it, 5) },
            )

        IngestService(db, KEY, nowMs = { clock }).accept(
            MetrikJson.encodeToString(
                Frame(
                    apiKey = KEY,
                    service = service,
                    instance = "pod-a",
                    windowStart = windowStart,
                    windowSeq = windowSeq,
                    packetIndex = 0,
                    packetCount = 1,
                    routes = routes,
                ),
            ),
        )
    }

    private fun series(
        status: Int,
        count: Int,
        durationMs: Long,
    ) = RouteSeries(
        method = "GET",
        route = "/orders/{id}",
        status = status,
        count = count,
        sumMs = count * durationMs,
        maxMs = durationMs.toInt(),
        buckets = Histogram().also { histogram -> repeat(count) { histogram.record(durationMs) } },
    )

    @Test
    fun `a spike of errors on an idle service should not wake anyone`() =
        runTest {
            // Given — 1 ошибка из 3 это 33 %, но статистически это шум.
            ingest(okCount = 2, errorCount = 1)

            // When
            worker().tick()

            // Then
            assertTrue(notifier.messages.isEmpty(), "unexpected alert: ${notifier.messages}")
        }

    @Test
    fun `a real error rate should fire once and not repeat every tick`() =
        runTest {
            // Given — 40 из 100 запросов упали.
            ingest(okCount = 60, errorCount = 40)
            val alerts = worker()

            // When
            alerts.tick()
            alerts.tick()
            alerts.tick()

            // Then — нотификация уходит на переходе, а не на каждой проверке.
            assertEquals(1, notifier.messages.size)
            assertTrue(notifier.messages.single().contains("error_rate"))
        }

    @Test
    fun `recovery should be reported too`() =
        runTest {
            // Given
            ingest(okCount = 60, errorCount = 40)
            val alerts = worker()
            alerts.tick()

            // When — правило смотрит на две последние минуты, поэтому ждём, пока плохое окно
            // выйдет из окна наблюдения, и присылаем чистое.
            clock += 3 * MINUTE
            ingest(okCount = 100, errorCount = 0, windowStart = WINDOW + 3 * MINUTE, windowSeq = 2)
            alerts.tick()

            // Then
            assertEquals(2, notifier.messages.size)
            assertTrue(notifier.messages.last().contains("recovered"), notifier.messages.last())
        }

    @Test
    fun `a silent service should trigger absent without claiming a cause`() =
        runTest {
            // Given — сервис был и замолчал.
            ingest(okCount = 100, errorCount = 0)

            // When
            clock += 10 * MINUTE
            worker().tick()

            // Then — метриками нельзя отличить «упал» от «оборвалась сеть до metrik».
            val absent = notifier.messages.single { it.contains("absent") }
            assertTrue(absent.contains("no data"), absent)
        }

    @Test
    fun `a per-service threshold should not loosen the others`() =
        runTest {
            // Given — шумному сервису поднят порог, остальным нет.
            ingest(okCount = 60, errorCount = 40, service = "legacy-import")
            ingest(okCount = 60, errorCount = 40, service = "orders-api")

            val noisyId =
                db
                    .fetchAll("SELECT id FROM services WHERE name = 'legacy-import'")
                    .getOrThrow()
                    .rows
                    .first()
            admin.updateRule(
                noisyId.get("id").asString().toLong(),
                AlertRuleView(AlertRuleIds.ERROR_RATE, threshold = 0.9, minCount = 20, windows = 1, enabled = true),
            )

            // When
            worker().tick()

            // Then
            val fired = notifier.messages.filter { it.contains("error_rate") }
            assertEquals(1, fired.size, "fired: $fired")
            assertTrue(fired.single().contains("orders-api"))
        }

    @Test
    fun `a failing notifier should not lose the alert state`() =
        runTest {
            // Given — Telegram недоступен.
            notifier.failing = true
            ingest(okCount = 60, errorCount = 40)
            val alerts = worker()

            // When
            alerts.tick()

            // Then — состояние сохранено, воркер жив, история записана.
            val active = alerts.active()
            assertEquals(1, active.size)
            assertEquals(ALERT_STATE_FIRING, active.single().state)
            assertTrue(alerts.history().isNotEmpty())
        }
}
