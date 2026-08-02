package ru.workinprogress.metrik.server.query

import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLongOrNull
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import ru.workinprogress.metrik.api.DeployMarker
import ru.workinprogress.metrik.api.Overview
import ru.workinprogress.metrik.api.RouteRow
import ru.workinprogress.metrik.api.ServiceSummary
import ru.workinprogress.metrik.api.SlowRow
import ru.workinprogress.metrik.api.Step
import ru.workinprogress.metrik.api.SystemPoint
import ru.workinprogress.metrik.api.TimePoint
import ru.workinprogress.metrik.api.TimeSeries
import ru.workinprogress.metrik.wire.Histogram
import ru.workinprogress.metrik.wire.MetrikJson
import ru.workinprogress.metrik.wire.isServerError
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS

/** Строка окна, как она лежит в базе: инстансы уже слиты. */
private class WindowRow(
    val windowStart: Long,
    val method: String,
    val route: String,
    val status: Int,
    val count: Long,
    val sumMs: Long,
    val maxMs: Long,
    val buckets: Histogram,
)

/**
 * Чтение агрегатов для дашборда.
 *
 * Перцентили считаются из **суммарной** гистограммы интервала: складывать перцентили нельзя,
 * складываются только бакеты. Инстансы к этому моменту уже слиты на записи.
 */
@OptIn(ExperimentalTime::class)
class QueryService(
    private val db: ISQLite,
    private val minuteRetentionMs: Long = 48 * HOUR_MS,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    /**
     * Сводка по сервисам за интервал.
     *
     * Период — параметр, а не константа: на дашборде есть переключатель диапазона, и он обязан
     * что-то менять. Переключатель, который ничего не делает, врёт не хуже неправильной цифры.
     */
    suspend fun services(
        from: Long = nowMs() - 5 * MINUTE_MS,
        to: Long = nowMs(),
    ): List<ServiceSummary> {
        val spanSeconds = ((to - from).coerceAtLeast(1_000L)) / 1000.0

        return rows("SELECT id, name FROM services ORDER BY name").map { row ->
            val id = row.get("id").asLong()
            val name = row.get("name").asString()
            val windows = windowRows(id, from, to)
            val merged = merge(windows)
            val instanceRow =
                rows(
                    Statement
                        .create(
                            """
                            SELECT COUNT(*) AS c, MAX(last_seen) AS seen, MAX(clock_skew) AS skew
                            FROM instances WHERE service_id = :id
                            """.trimIndent(),
                        ).bind("id", id),
                ).first()

            ServiceSummary(
                id = id,
                name = name,
                requestsPerSecond = merged.count.toDouble() / spanSeconds,
                errorRate = merged.errorRate,
                p95Ms = merged.histogram.percentileMs(0.95),
                lastSeenAt = instanceRow.get("seen").asLongOrNull(),
                instances = instanceRow.get("c").asInt(),
                clockSkew = (instanceRow.get("skew").asLongOrNull() ?: 0L) > 0,
            )
        }
    }

    suspend fun overview(
        serviceId: Long,
        from: Long,
        to: Long,
    ): Overview {
        val name = serviceName(serviceId)
        val merged = merge(windowRows(serviceId, from, to))

        return Overview(
            service = name,
            requests = merged.count,
            errors = merged.errors,
            errorRate = merged.errorRate,
            p50Ms = merged.histogram.percentileMs(0.5),
            p95Ms = merged.histogram.percentileMs(0.95),
            maxMs = merged.maxMs,
        )
    }

    /**
     * Ряд с шагом [step].
     *
     * Минутные окна живут ограниченное время; за пределами ретенции сервер молча отдаёт часовой
     * шаг, а не пустоту — в ответе видно, каким шагом данные на самом деле собраны.
     */
    suspend fun timeSeries(
        serviceId: Long,
        from: Long,
        to: Long,
        requested: Step,
    ): TimeSeries {
        val step = effectiveStep(requested, from)
        val stepMs = step.durationMs()
        val windows = windowRows(serviceId, from, to)
        val partial = partialWindows(serviceId, from, to)

        val points =
            windows
                .groupBy { row -> row.windowStart - row.windowStart % stepMs }
                .entries
                .sortedBy { entry -> entry.key }
                .map { (bucketStart, rowsInBucket) ->
                    val merged = merge(rowsInBucket)
                    TimePoint(
                        at = bucketStart,
                        requestsPerSecond = merged.count.toDouble() / (stepMs / 1000.0),
                        errorRate = merged.errorRate,
                        p50Ms = merged.histogram.percentileMs(0.5),
                        p95Ms = merged.histogram.percentileMs(0.95),
                        maxMs = merged.maxMs,
                        partial = rowsInBucket.any { row -> row.windowStart in partial },
                    )
                }

        return TimeSeries(step = step, points = points, deploys = deploys(serviceId, from, to))
    }

    suspend fun routes(
        serviceId: Long,
        from: Long,
        to: Long,
    ): List<RouteRow> =
        windowRows(serviceId, from, to)
            .groupBy { row -> Triple(row.method, row.route, row.status) }
            .map { (key, rowsInGroup) ->
                val merged = merge(rowsInGroup)
                RouteRow(
                    method = key.first,
                    route = key.second,
                    status = key.third,
                    count = merged.count,
                    p50Ms = merged.histogram.percentileMs(0.5),
                    p95Ms = merged.histogram.percentileMs(0.95),
                    maxMs = merged.maxMs,
                )
            }.sortedByDescending { it.count }

    suspend fun slow(
        serviceId: Long,
        from: Long,
        to: Long = nowMs(),
        limit: Int = 100,
    ): List<SlowRow> =
        rows(
            Statement
                .create(
                    """
                    SELECT method, route, status, duration_ms, ts FROM slow_samples
                    WHERE service_id = :id AND ts BETWEEN :from AND :to
                    ORDER BY duration_ms DESC LIMIT ${limit.coerceIn(1, 500)}
                    """.trimIndent(),
                ).bind("id", serviceId)
                .bind("from", from)
                .bind("to", to),
        ).map { row ->
            SlowRow(
                method = row.get("method").asString(),
                route = row.get("route").asString(),
                status = row.get("status").asInt(),
                durationMs = row.get("duration_ms").asInt(),
                at = row.get("ts").asLong(),
            )
        }

    suspend fun system(
        serviceId: Long,
        from: Long,
        to: Long,
    ): List<SystemPoint> =
        rows(
            Statement
                .create(
                    """
                    SELECT i.instance_key AS instance, s.window_start, s.heap_used, s.heap_max,
                           s.cpu_permille, s.threads, s.gc_count, s.gc_ms
                    FROM system_windows s
                    JOIN instances i ON i.id = s.instance_id
                    WHERE i.service_id = :id AND s.window_start BETWEEN :from AND :to
                    ORDER BY s.window_start
                    """.trimIndent(),
                ).bind("id", serviceId)
                .bind("from", from)
                .bind("to", to),
        ).map { row ->
            SystemPoint(
                instance = row.get("instance").asString(),
                at = row.get("window_start").asLong(),
                heapUsedBytes = row.get("heap_used").asLong(),
                heapMaxBytes = row.get("heap_max").asLongOrNull(),
                cpuPermille = row.get("cpu_permille").asInt(),
                threads = row.get("threads").asInt(),
                gcCollections = row.get("gc_count").asLongOrNull()?.toInt(),
                gcMs = row.get("gc_ms").asLongOrNull(),
            )
        }

    suspend fun deploys(
        serviceId: Long,
        from: Long,
        to: Long,
    ): List<DeployMarker> =
        rows(
            Statement
                .create(
                    """
                    SELECT release, MIN(first_seen) AS at FROM deploys
                    WHERE service_id = :id AND first_seen BETWEEN :from AND :to
                    GROUP BY release ORDER BY at
                    """.trimIndent(),
                ).bind("id", serviceId)
                .bind("from", from)
                .bind("to", to),
        ).map { row -> DeployMarker(release = row.get("release").asString(), at = row.get("at").asLong()) }

    suspend fun serviceIdByName(name: String): Long? =
        rows(Statement.create("SELECT id FROM services WHERE name = :name").bind("name", name))
            .firstOrNull()
            ?.get("id")
            ?.asLong()

    private suspend fun serviceName(serviceId: Long): String =
        rows(Statement.create("SELECT name FROM services WHERE id = :id").bind("id", serviceId))
            .firstOrNull()
            ?.get("name")
            ?.asString()
            ?: "unknown"

    private fun effectiveStep(
        requested: Step,
        from: Long,
    ): Step = if (requested == Step.MINUTE && from < nowMs() - minuteRetentionMs) Step.HOUR else requested

    private fun Step.durationMs(): Long =
        when (this) {
            Step.MINUTE -> MINUTE_MS
            Step.HOUR -> HOUR_MS
            Step.DAY -> DAY_MS
        }

    /** Окна, от которых пришли не все пакеты: неполноту считаем при чтении, а не храним. */
    private suspend fun partialWindows(
        serviceId: Long,
        from: Long,
        to: Long,
    ): Set<Long> =
        rows(
            Statement
                .create(
                    """
                    SELECT window_start, packet_count, COUNT(*) AS received
                    FROM window_receipts
                    WHERE service_id = :id AND window_start BETWEEN :from AND :to
                    GROUP BY window_start, instance_id, packet_count
                    """.trimIndent(),
                ).bind("id", serviceId)
                .bind("from", from)
                .bind("to", to),
        ).filter { row -> row.get("received").asLong() < row.get("packet_count").asLong() }
            .map { row -> row.get("window_start").asLong() }
            .toSet()

    private suspend fun windowRows(
        serviceId: Long,
        from: Long,
        to: Long,
    ): List<WindowRow> =
        rows(
            Statement
                .create(
                    """
                    SELECT window_start, method, route, status, count, sum_ms, max_ms, buckets
                    FROM route_windows
                    WHERE service_id = :id AND window_start BETWEEN :from AND :to
                    """.trimIndent(),
                ).bind("id", serviceId)
                .bind("from", from)
                .bind("to", to),
        ).map { row ->
            WindowRow(
                windowStart = row.get("window_start").asLong(),
                method = row.get("method").asString(),
                route = row.get("route").asString(),
                status = row.get("status").asInt(),
                count = row.get("count").asLong(),
                sumMs = row.get("sum_ms").asLong(),
                maxMs = row.get("max_ms").asLong(),
                buckets = Histogram.fromSparse(MetrikJson.decodeFromString(row.get("buckets").asString())),
            )
        }

    private suspend fun rows(sql: String): List<ResultSet.Row> = db.fetchAll(sql).getOrThrow().rows

    private suspend fun rows(statement: Statement): List<ResultSet.Row> = db.fetchAll(statement).getOrThrow().rows

    private fun merge(windows: List<WindowRow>): Merged {
        val histogram = Histogram()
        var count = 0L
        var errors = 0L
        var maxMs = 0L

        windows.forEach { row ->
            histogram.merge(row.buckets)
            count += row.count
            if (isServerError(row.status)) errors += row.count
            if (row.maxMs > maxMs) maxMs = row.maxMs
        }

        return Merged(histogram, count, errors, maxMs)
    }

    private class Merged(
        val histogram: Histogram,
        val count: Long,
        val errors: Long,
        val maxMs: Long,
    ) {
        val errorRate: Double get() = if (count == 0L) 0.0 else errors.toDouble() / count
    }
}
