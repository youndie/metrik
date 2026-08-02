package ru.workinprogress.metrik.server.retention

import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.workinprogress.metrik.wire.Histogram
import ru.workinprogress.metrik.wire.MetrikJson
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS

const val GRANULARITY_HOUR = "hour"
const val GRANULARITY_DAY = "day"

/**
 * Свёртка и уборка.
 *
 * Минутные окна живут недолго (по умолчанию 48 часов), дальше остаются часовые и дневные роллапы:
 * без этого один сервис за неделю занимал бы сотни мегабайт, что несовместимо со словом «лёгкий».
 */
@OptIn(ExperimentalTime::class)
class RetentionWorker(
    private val db: ISQLite,
    private val minuteRetentionMs: Long = 48 * HOUR_MS,
    private val hourRetentionMs: Long = 90 * DAY_MS,
    private val slowRetentionMs: Long = DAY_MS,
    private val instanceRetentionMs: Long = DAY_MS,
    private val intervalMs: Long = HOUR_MS,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job =
            scope.launch {
                while (currentlyActive()) {
                    try {
                        tick()
                    } catch (cause: CancellationException) {
                        throw cause
                    } catch (_: Throwable) {
                        // Уборщик, падающий от одной ошибки, оставит базу расти молча.
                    }
                    delay(intervalMs)
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    suspend fun tick() {
        val now = nowMs()

        rollup(GRANULARITY_HOUR, HOUR_MS, now)
        rollup(GRANULARITY_DAY, DAY_MS, now)
        purge(now)
    }

    /**
     * Сворачивает минутные окна в бакеты [bucketMs].
     *
     * Окна моложе пяти минут не трогаются: в них ещё долетают пакеты отстающих инстансов, и
     * свернуть такое окно значило бы потерять то, что придёт следом.
     */
    private suspend fun rollup(
        granularity: String,
        bucketMs: Long,
        now: Long,
    ) {
        val safeUntil = now - 5 * MINUTE_MS
        val alreadyDone =
            db
                .fetchAll(
                    Statement
                        .create("SELECT IFNULL(MAX(bucket_start), 0) AS last FROM route_rollups WHERE granularity = :g")
                        .bind("g", granularity),
                ).getOrThrow()
                .rows
                .first()
                .get("last")
                .asLong()

        val rows =
            db
                .fetchAll(
                    Statement
                        .create(
                            """
                            SELECT service_id, window_start, method, route, status, count, sum_ms, max_ms, buckets
                            FROM route_windows
                            WHERE window_start >= :from AND window_start < :until
                            """.trimIndent(),
                        ).bind("from", alreadyDone)
                        .bind("until", safeUntil),
                ).getOrThrow()
                .rows

        rows
            .groupBy { row ->
                RollupKey(
                    serviceId = row.get("service_id").asLong(),
                    bucketStart = row.get("window_start").asLong().let { it - it % bucketMs },
                    method = row.get("method").asString(),
                    route = row.get("route").asString(),
                    status = row.get("status").asLong(),
                )
            }.forEach { (key, group) ->
                if (key.bucketStart + bucketMs > safeUntil) return@forEach

                val histogram = Histogram()
                var count = 0L
                var sum = 0L
                var max = 0L

                group.forEach { row ->
                    histogram.merge(Histogram.fromSparse(MetrikJson.decodeFromString(row.get("buckets").asString())))
                    count += row.get("count").asLong()
                    sum += row.get("sum_ms").asLong()
                    max = maxOf(max, row.get("max_ms").asLong())
                }

                db
                    .execute(
                        Statement
                            .create(
                                """
                                INSERT OR REPLACE INTO route_rollups
                                    (service_id, granularity, bucket_start, method, route, status, count, sum_ms, max_ms, buckets)
                                VALUES (:service, :g, :bucket, :method, :route, :status, :count, :sum, :max, :buckets)
                                """.trimIndent(),
                            ).bind("service", key.serviceId)
                            .bind("g", granularity)
                            .bind("bucket", key.bucketStart)
                            .bind("method", key.method)
                            .bind("route", key.route)
                            .bind("status", key.status)
                            .bind("count", count)
                            .bind("sum", sum)
                            .bind("max", max)
                            .bind("buckets", MetrikJson.encodeToString(histogram.toSparse())),
                    ).getOrThrow()
            }
    }

    private suspend fun purge(now: Long) {
        delete("DELETE FROM route_windows WHERE window_start < :t", now - minuteRetentionMs)
        delete("DELETE FROM window_receipts WHERE window_start < :t", now - minuteRetentionMs)
        delete("DELETE FROM system_windows WHERE window_start < :t", now - minuteRetentionMs)
        delete("DELETE FROM slow_samples WHERE ts < :t", now - slowRetentionMs)
        delete("DELETE FROM route_rollups WHERE granularity = '$GRANULARITY_HOUR' AND bucket_start < :t", now - hourRetentionMs)

        // Инстансы в k8s пересоздаются каждый выкат: без уборки их список растёт линейно по деплоям
        // и ничего не сообщает.
        delete("DELETE FROM instances WHERE last_seen < :t", now - instanceRetentionMs)

        db.execute("PRAGMA incremental_vacuum;").getOrThrow()
    }

    private suspend fun delete(
        sql: String,
        threshold: Long,
    ) {
        db.execute(Statement.create(sql).bind("t", threshold)).getOrThrow()
    }

    private suspend fun currentlyActive(): Boolean = kotlin.coroutines.coroutineContext.isActive

    private class RollupKey(
        val serviceId: Long,
        val bucketStart: Long,
        val method: String,
        val route: String,
        val status: Long,
    ) {
        override fun equals(other: Any?): Boolean =
            other is RollupKey &&
                other.serviceId == serviceId &&
                other.bucketStart == bucketStart &&
                other.method == method &&
                other.route == route &&
                other.status == status

        override fun hashCode(): Int {
            var result = serviceId.hashCode()
            result = 31 * result + bucketStart.hashCode()
            result = 31 * result + method.hashCode()
            result = 31 * result + route.hashCode()
            result = 31 * result + status.hashCode()
            return result
        }
    }
}
