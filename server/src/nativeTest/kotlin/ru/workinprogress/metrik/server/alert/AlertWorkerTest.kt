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
import kotlin.test.assertFalse
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
    ): Boolean {
        if (failing) throw IllegalStateException("telegram is down")
        messages += text
        return true
    }
}

class AlertWorkerTest {
    private val dbPath = "/tmp/metrik-alert-test.db"
    private val db = openDatabase(dbPath)
    private val admin = AdminService(db, nowMs = { clock })
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
    fun `a muted rule should stay visible but stop notifying`() =
        runTest {
            // Given — правило горит и заглушено на час.
            ingest(okCount = 60, errorCount = 40)
            val id =
                db
                    .fetchAll("SELECT id FROM services WHERE name = 'orders-api'")
                    .getOrThrow()
                    .rows
                    .first()
                    .get("id")
                    .asString()
                    .toLong()
            admin.mute(id, AlertRuleIds.ERROR_RATE, clock + 60 * MINUTE)

            // When
            val alerts = worker()
            alerts.tick()

            // Then — уведомления молчат, но алерт горит: «заглушил» это «не буди», а не «всё хорошо».
            assertTrue(notifier.messages.none { it.contains("error_rate") }, "muted rule notified: ${notifier.messages}")
            assertTrue(alerts.active().any { it.ruleId == AlertRuleIds.ERROR_RATE })
            assertTrue(alerts.active().first { it.ruleId == AlertRuleIds.ERROR_RATE }.mutedUntil != null)
        }

    @Test
    fun `an expired mute should not silence anything`() =
        runTest {
            // Given — заглушение уже истекло.
            ingest(okCount = 60, errorCount = 40)
            val id =
                db
                    .fetchAll("SELECT id FROM services WHERE name = 'orders-api'")
                    .getOrThrow()
                    .rows
                    .first()
                    .get("id")
                    .asString()
                    .toLong()
            admin.mute(id, AlertRuleIds.ERROR_RATE, clock - MINUTE)

            // When
            worker().tick()

            // Then
            assertTrue(notifier.messages.any { it.contains("error_rate") })
        }

    @Test
    fun `a test notification without a configured notifier should report not delivered`() =
        runTest {
            // Given — токена нет, доставлять некуда.
            val silent = AlertWorker(db, admin, NoopNotifier, nowMs = { clock })

            // When / Then — «отправлено» здесь было бы враньём ровно там, где проверяют настройку.
            assertFalse(silent.sendTest())
        }

    @Test
    fun `a test notification should report whether it was delivered`() =
        runTest {
            // Given / When
            val ok = worker().sendTest()

            // Then
            assertTrue(ok)
            assertTrue(notifier.messages.any { it.contains("тестовое") })

            // Given — доставка сломана.
            notifier.failing = true

            // When / Then — UI обязан узнать правду, а не бодрое «отправлено».
            assertFalse(worker().sendTest())
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
