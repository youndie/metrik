package ru.workinprogress.metrik.server.query

import io.github.smyrgeorge.sqlx4k.Statement
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import ru.workinprogress.metrik.api.Step
import ru.workinprogress.metrik.server.alert.ALERT_STATE_FIRING
import ru.workinprogress.metrik.server.ingest.IngestResult
import ru.workinprogress.metrik.server.ingest.IngestService
import ru.workinprogress.metrik.server.openDatabase
import ru.workinprogress.metrik.wire.Frame
import ru.workinprogress.metrik.wire.Histogram
import ru.workinprogress.metrik.wire.MetrikJson
import ru.workinprogress.metrik.wire.RouteSeries
import ru.workinprogress.metrik.wire.SlowSample
import ru.workinprogress.metrik.wire.encodeStatus
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val KEY = "query-test-key"
private const val WINDOW = 1_754_049_600_000L
private const val MINUTE = 60_000L

class QueryServiceTest {
    private val dbPath = "/tmp/metrik-query-test.db"
    private val db = openDatabase(dbPath)
    private val now = WINDOW + 5 * MINUTE
    private val ingest = IngestService(db, KEY, nowMs = { now })
    private val query = QueryService(db, nowMs = { now })

    @AfterTest
    fun cleanup() {
        FileSystem.SYSTEM.delete(dbPath.toPath(), mustExist = false)
    }

    private suspend fun send(
        windowStart: Long = WINDOW,
        instance: String = "pod-a",
        packetIndex: Int = 0,
        packetCount: Int = 1,
        windowSeq: Long = 1,
        routes: List<RouteSeries> = listOf(okSeries()),
        slow: List<SlowSample>? = null,
    ): IngestResult =
        ingest.accept(
            MetrikJson.encodeToString(
                Frame(
                    apiKey = KEY,
                    service = "orders-api",
                    instance = instance,
                    windowStart = windowStart,
                    windowSeq = windowSeq,
                    packetIndex = packetIndex,
                    packetCount = packetCount,
                    routes = routes,
                    slow = slow,
                ),
            ),
        )

    private fun okSeries(
        count: Int = 60,
        durations: List<Long> = List(60) { 10L },
    ) = RouteSeries(
        method = "GET",
        route = "/orders/{id}",
        status = encodeStatus(200),
        count = count,
        sumMs = durations.sum(),
        maxMs = durations.max().toInt(),
        buckets = Histogram().also { histogram -> durations.forEach(histogram::record) },
    )

    private fun errorSeries(count: Int = 40) =
        RouteSeries(
            method = "GET",
            route = "/orders/{id}",
            status = encodeStatus(503),
            count = count,
            sumMs = count * 5L,
            maxMs = 5,
            buckets = Histogram().also { histogram -> repeat(count) { histogram.record(5) } },
        )

    private suspend fun serviceId(): Long = query.serviceIdByName("orders-api")!!

    /** Пишет состояние правила напрямую: таблица `alert_states` — это и есть стык воркера и чтения. */
    private suspend fun fireRule(
        serviceId: Long,
        ruleId: String,
        state: String,
    ) {
        db
            .execute(
                Statement
                    .create(
                        """
                        INSERT INTO alert_states (service_id, rule_id, state, since)
                        VALUES (:id, :rule, :state, :since)
                        """.trimIndent(),
                    ).bind("id", serviceId)
                    .bind("rule", ruleId)
                    .bind("state", state)
                    .bind("since", now),
            ).getOrThrow()
    }

    @Test
    fun `the service list should summarise the recent window`() =
        runTest {
            // Given
            send(windowStart = now - MINUTE)

            // When
            val services = query.services()

            // Then
            assertEquals(1, services.size)
            assertEquals("orders-api", services.single().name)
            assertEquals(1, services.single().instances)
            assertTrue(services.single().requestsPerSecond > 0)
        }

    /**
     * Поле `firingAlerts` сервер когда-то не заполнял вовсе: на «Алертах» сервис горел, а его
     * карточка на «Обзоре» оставалась зелёной с подписью «ок». Дашборд, который в одном месте
     * показывает аварию, а в другом её же прячет, хуже, чем дашборд без карточек.
     */
    @Test
    fun `the service list should carry the rules that are firing`() =
        runTest {
            // Given — сервис есть, и по нему горит одно правило, а второе погасло.
            send()
            val id = serviceId()
            fireRule(id, "absent", ALERT_STATE_FIRING)
            fireRule(id, "error_rate", "OK")

            // When
            val summary = query.services().single()

            // Then — горящее видно, погасшее не мешается.
            assertEquals(listOf("absent"), summary.firingAlerts)
        }

    @Test
    fun `a service with nothing firing should carry an empty list`() =
        runTest {
            // Given
            send()

            // When / Then
            assertTrue(
                query
                    .services()
                    .single()
                    .firingAlerts
                    .isEmpty(),
            )
        }

    @Test
    fun `the service list should honour the requested period`() =
        runTest {
            // Given
            send(windowStart = WINDOW)

            // When — узкий интервал окно не захватывает, широкий захватывает.
            val narrow = query.services(from = now - MINUTE, to = now)
            val wide = query.services(from = WINDOW - MINUTE, to = now)

            // Then — переключатель диапазона на дашборде обязан что-то менять.
            assertEquals(0.0, narrow.single().requestsPerSecond)
            assertTrue(wide.single().requestsPerSecond > 0)
        }

    @Test
    fun `error rate should count only server errors`() =
        runTest {
            // Given — 60 успешных и 40 пятисоток.
            send(routes = listOf(okSeries(), errorSeries()))

            // When
            val overview = query.overview(serviceId(), WINDOW - MINUTE, now)

            // Then
            assertEquals(100, overview.requests)
            assertEquals(40, overview.errors)
            assertEquals(0.4, overview.errorRate)
        }

    @Test
    fun `percentiles should come from the merged histogram of the interval`() =
        runTest {
            // Given — две минуты: быстрая и медленная.
            send(windowStart = WINDOW, routes = listOf(okSeries(durations = List(60) { 10L })))
            send(windowStart = WINDOW + MINUTE, windowSeq = 2, routes = listOf(okSeries(durations = List(60) { 500L })))

            // When
            val overview = query.overview(serviceId(), WINDOW, now)

            // Then — p95 берётся из суммарной гистограммы, а не как среднее двух перцентилей.
            assertTrue(overview.p95Ms > 400, "p95 was ${overview.p95Ms}")
            assertTrue(overview.p50Ms < 100, "p50 was ${overview.p50Ms}")
        }

    @Test
    fun `a time series should have one point per window`() =
        runTest {
            // Given
            send(windowStart = WINDOW)
            send(windowStart = WINDOW + MINUTE, windowSeq = 2)

            // When
            val series = query.timeSeries(serviceId(), WINDOW, now, Step.MINUTE)

            // Then
            assertEquals(Step.MINUTE, series.step)
            assertEquals(2, series.points.size)
            assertEquals(1.0, series.points.first().requestsPerSecond)
        }

    @Test
    fun `a window missing a packet should be marked partial`() =
        runTest {
            // Given — окно из двух пакетов, второй не дошёл.
            send(packetIndex = 0, packetCount = 2)

            // When
            val series = query.timeSeries(serviceId(), WINDOW, now, Step.MINUTE)

            // Then — UI обязан нарисовать разрыв, а не значение «меньше, чем было».
            assertTrue(series.points.single().partial)
        }

    @Test
    fun `a complete window should not be marked partial`() =
        runTest {
            // Given
            send(packetIndex = 0, packetCount = 2, routes = listOf(okSeries()))
            send(packetIndex = 1, packetCount = 2, routes = listOf(errorSeries()))

            // When
            val series = query.timeSeries(serviceId(), WINDOW, now, Step.MINUTE)

            // Then
            assertFalse(series.points.single().partial)
        }

    @Test
    fun `minute step beyond retention should fall back to hours`() =
        runTest {
            // Given — запрошен минутный шаг за пределами хранения минуток.
            send()

            // When
            val series = query.timeSeries(serviceId(), now - 10 * 24 * 60 * MINUTE, now, Step.MINUTE)

            // Then — сервер отдаёт часовой шаг и говорит об этом в ответе, а не пустоту.
            assertEquals(Step.HOUR, series.step)
        }

    @Test
    fun `the routes table should keep statuses apart and sort by volume`() =
        runTest {
            // Given
            send(routes = listOf(okSeries(), errorSeries()))

            // When
            val routes = query.routes(serviceId(), WINDOW, now)

            // Then
            assertEquals(2, routes.size)
            assertEquals(listOf(2, 503), routes.map { it.status }.sorted())
            assertTrue(routes.first().count >= routes.last().count)
        }

    @Test
    fun `slow samples should come back sorted by duration`() =
        runTest {
            // Given
            send(
                slow =
                    listOf(
                        SlowSample("POST", "/orders", encodeStatus(503), 8_000, WINDOW),
                        SlowSample("GET", "/orders/{id}", encodeStatus(200), 3_000, WINDOW),
                    ),
            )

            // When
            val slow = query.slow(serviceId(), WINDOW - MINUTE)

            // Then
            assertEquals(listOf(8_000, 3_000), slow.map { it.durationMs })
        }

    @Test
    fun `an empty interval should return no points rather than zeros`() =
        runTest {
            // Given — «данных нет» и «нагрузки нет» это разные вещи.
            send()

            // When
            val series = query.timeSeries(serviceId(), WINDOW - 10 * MINUTE, WINDOW - MINUTE, Step.MINUTE)

            // Then
            assertTrue(series.points.isEmpty())
        }
}
