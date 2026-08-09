package ru.workinprogress.metrik.server.ingest

import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.coroutines.TransactionContext
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLongOrNull
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import ru.workinprogress.metrik.wire.Frame
import ru.workinprogress.metrik.wire.Histogram
import ru.workinprogress.metrik.wire.MetrikJson
import ru.workinprogress.metrik.wire.PROTOCOL_VERSION
import ru.workinprogress.metrik.wire.RouteSeries
import ru.workinprogress.metrik.wire.SlowSample
import ru.workinprogress.metrik.wire.SystemSnapshot
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Что случилось с пришедшей датаграммой. Всё, кроме [ACCEPTED], видно в `/api/self`. */
enum class IngestResult {
    ACCEPTED,
    DUPLICATE,
    MALFORMED,
    UNKNOWN_VERSION,
    BAD_KEY,
    CLOCK_SKEW,
}

/**
 * Приём и раскладка окна по таблицам.
 *
 * Инстансы сливаются **на записи**: три пода, приславшие окно за одну минуту, дают одну строку.
 * Поэтому повторно доставленный пакет обязан отбрасываться — иначе он молча удвоит цифры.
 * Защита — расписка в `window_receipts` с первичным ключом `(service, instance, window, packet)`.
 */
@OptIn(ExperimentalTime::class)
class IngestService(
    private val db: ISQLite,
    private val ingestKey: String,
    val counters: IngestCounters = IngestCounters(),
    private val clockSkewToleranceMs: Long = 5 * 60 * 1000,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    suspend fun accept(payload: String): IngestResult {
        val frame =
            runCatching { MetrikJson.decodeFromString<Frame>(payload) }.getOrNull()
                ?: return counters.record(IngestResult.MALFORMED)

        if (frame.version != PROTOCOL_VERSION) return counters.record(IngestResult.UNKNOWN_VERSION)
        if (frame.apiKey != ingestKey) return counters.record(IngestResult.BAD_KEY)

        return counters.record(persist(frame))
    }

    private suspend fun persist(frame: Frame): IngestResult =
        TransactionContext.withCurrent(db) {
            val now = nowMs()
            val serviceId = upsertService(frame.service, now)
            val skewed = abs(now - frame.windowStart) > clockSkewToleranceMs
            val instanceId = upsertInstance(serviceId, frame, now, skewed)

            // Окно с разъехавшимися часами нельзя класть в ряд: оно нарисовало бы точку в будущем
            // или переписало прошлое. Инстанс при этом помечен, и это видно в UI.
            if (skewed) {
                IngestResult.CLOCK_SKEW
            } else if (!registerReceipt(serviceId, instanceId, frame)) {
                IngestResult.DUPLICATE
            } else {
                frame.routes.forEach { series -> mergeSeries(serviceId, frame.windowStart, series) }
                frame.system?.let { snapshot -> writeSystem(instanceId, frame.windowStart, snapshot) }
                frame.slow?.forEach { sample -> writeSlow(serviceId, sample) }
                IngestResult.ACCEPTED
            }
        }

    private suspend fun TransactionContext.rows(statement: Statement): List<ResultSet.Row> = fetchAll(statement).getOrThrow().rows

    private suspend fun TransactionContext.upsertService(
        name: String,
        now: Long,
    ): Long {
        // Регистрации сервисов нет: ключ один на инсталляцию, имя и есть идентификатор.
        execute(
            Statement
                .create("INSERT OR IGNORE INTO services (name, created_at) VALUES (:name, :now)")
                .bind("name", name)
                .bind("now", now),
        ).getOrThrow()

        return rows(Statement.create("SELECT id FROM services WHERE name = :name").bind("name", name))
            .first()
            .get("id")
            .asLong()
    }

    private suspend fun TransactionContext.upsertInstance(
        serviceId: Long,
        frame: Frame,
        now: Long,
        skewed: Boolean,
    ): Long {
        execute(
            Statement
                .create(
                    """
                    INSERT OR IGNORE INTO instances (service_id, instance_key, last_seen, clock_skew)
                    VALUES (:serviceId, :key, :now, 0)
                    """.trimIndent(),
                ).bind("serviceId", serviceId)
                .bind("key", frame.instance)
                .bind("now", now),
        ).getOrThrow()

        val row =
            rows(
                Statement
                    .create(
                        """
                        SELECT id, release, last_window_seq FROM instances
                        WHERE service_id = :serviceId AND instance_key = :key
                        """.trimIndent(),
                    ).bind("serviceId", serviceId)
                    .bind("key", frame.instance),
            ).first()

        val instanceId = row.get("id").asLong()
        val previousRelease = row.get("release").asStringOrNull()
        val previousSeq = row.get("last_window_seq").asLongOrNull()

        // Дырка в номерах окон — потерянные окна. Строк за них не появится, и UI нарисует разрыв;
        // счётчик нужен, чтобы отличить потерю от «сервиса не было».
        if (previousSeq != null && frame.windowSeq > previousSeq + 1) {
            counters.recordMissedWindows((frame.windowSeq - previousSeq - 1).toInt())
        }

        execute(
            Statement
                .create(
                    """
                    UPDATE instances
                    SET last_seen = :now,
                        release = COALESCE(:release, release),
                        last_window_seq = :seq,
                        clock_skew = :skew
                    WHERE id = :id
                    """.trimIndent(),
                ).bind("now", now)
                .bind("release", frame.release)
                .bind("seq", frame.windowSeq)
                .bind("skew", if (skewed) 1 else 0)
                .bind("id", instanceId),
        ).getOrThrow()

        val release = frame.release
        if (release != null && release != previousRelease) {
            execute(
                Statement
                    .create(
                        """
                        INSERT INTO deploys (service_id, instance_id, release, first_seen)
                        VALUES (:serviceId, :instanceId, :release, :now)
                        """.trimIndent(),
                    ).bind("serviceId", serviceId)
                    .bind("instanceId", instanceId)
                    .bind("release", release)
                    .bind("now", now),
            ).getOrThrow()
        }

        return instanceId
    }

    /** @return false, если такой пакет уже приходил. */
    private suspend fun TransactionContext.registerReceipt(
        serviceId: Long,
        instanceId: Long,
        frame: Frame,
    ): Boolean {
        val seen =
            rows(
                Statement
                    .create(
                        """
                        SELECT COUNT(*) AS c FROM window_receipts
                        WHERE service_id = :serviceId AND instance_id = :instanceId
                          AND window_start = :window AND packet_index = :index
                        """.trimIndent(),
                    ).bind("serviceId", serviceId)
                    .bind("instanceId", instanceId)
                    .bind("window", frame.windowStart)
                    .bind("index", frame.packetIndex),
            ).first().get("c").asLong() > 0

        if (seen) return false

        execute(
            Statement
                .create(
                    """
                    INSERT INTO window_receipts
                        (service_id, instance_id, window_start, packet_index, packet_count, received_at)
                    VALUES (:serviceId, :instanceId, :window, :index, :count, :now)
                    """.trimIndent(),
                ).bind("serviceId", serviceId)
                .bind("instanceId", instanceId)
                .bind("window", frame.windowStart)
                .bind("index", frame.packetIndex)
                .bind("count", frame.packetCount)
                .bind("now", nowMs()),
        ).getOrThrow()

        return true
    }

    private suspend fun TransactionContext.mergeSeries(
        serviceId: Long,
        windowStart: Long,
        series: RouteSeries,
    ) {
        val existing =
            rows(
                Statement
                    .create(
                        """
                        SELECT count, sum_ms, max_ms, buckets FROM route_windows
                        WHERE service_id = :serviceId AND window_start = :window
                          AND method = :method AND route = :route AND status = :status
                        """.trimIndent(),
                    ).bind("serviceId", serviceId)
                    .bind("window", windowStart)
                    .bind("method", series.method)
                    .bind("route", series.route)
                    .bind("status", series.status),
            ).firstOrNull()

        // Гистограммы складываются побакетно — в SQL этого не выразить, поэтому read-modify-write
        // внутри той же транзакции, что и приём. Отсюда же требование идемпотентности пакета.
        val mergedBuckets =
            if (existing == null) {
                series.buckets
            } else {
                Histogram.fromSparse(MetrikJson.decodeFromString(existing.get("buckets").asString())) + series.buckets
            }

        val encodedBuckets = MetrikJson.encodeToString(mergedBuckets.toSparse())

        if (existing == null) {
            execute(
                Statement
                    .create(
                        """
                        INSERT INTO route_windows
                            (service_id, window_start, method, route, status, count, sum_ms, max_ms, buckets)
                        VALUES (:serviceId, :window, :method, :route, :status, :count, :sum, :max, :buckets)
                        """.trimIndent(),
                    ).bind("serviceId", serviceId)
                    .bind("window", windowStart)
                    .bind("method", series.method)
                    .bind("route", series.route)
                    .bind("status", series.status)
                    .bind("count", series.count)
                    .bind("sum", series.sumMs)
                    .bind("max", series.maxMs)
                    .bind("buckets", encodedBuckets),
            ).getOrThrow()
        } else {
            execute(
                Statement
                    .create(
                        """
                        UPDATE route_windows
                        SET count = :count, sum_ms = :sum, max_ms = :max, buckets = :buckets
                        WHERE service_id = :serviceId AND window_start = :window
                          AND method = :method AND route = :route AND status = :status
                        """.trimIndent(),
                    ).bind("count", existing.get("count").asLong() + series.count)
                    .bind("sum", existing.get("sum_ms").asLong() + series.sumMs)
                    .bind("max", maxOf(existing.get("max_ms").asLong(), series.maxMs.toLong()))
                    .bind("buckets", encodedBuckets)
                    .bind("serviceId", serviceId)
                    .bind("window", windowStart)
                    .bind("method", series.method)
                    .bind("route", series.route)
                    .bind("status", series.status),
            ).getOrThrow()
        }
    }

    private suspend fun TransactionContext.writeSystem(
        instanceId: Long,
        windowStart: Long,
        snapshot: SystemSnapshot,
    ) {
        execute(
            Statement
                .create(
                    """
                    INSERT OR REPLACE INTO system_windows
                        (instance_id, window_start, heap_used, heap_max, cpu_permille, threads, uptime, gc_count, gc_ms, runtime)
                    VALUES (:instanceId, :window, :heapUsed, :heapMax, :cpu, :threads, :uptime, :gcCount, :gcMs, :runtime)
                    """.trimIndent(),
                ).bind("instanceId", instanceId)
                .bind("window", windowStart)
                .bind("heapUsed", snapshot.heapUsedBytes)
                .bind("heapMax", snapshot.heapMaxBytes)
                .bind("cpu", snapshot.cpuPermille)
                .bind("threads", snapshot.threads)
                .bind("uptime", snapshot.uptimeSeconds)
                .bind("gcCount", snapshot.gc?.collections)
                .bind("gcMs", snapshot.gc?.totalMs)
                .bind("runtime", snapshot.runtime),
        ).getOrThrow()
    }

    private suspend fun TransactionContext.writeSlow(
        serviceId: Long,
        sample: SlowSample,
    ) {
        execute(
            Statement
                .create(
                    """
                    INSERT INTO slow_samples (service_id, method, route, status, duration_ms, ts)
                    VALUES (:serviceId, :method, :route, :status, :duration, :ts)
                    """.trimIndent(),
                ).bind("serviceId", serviceId)
                .bind("method", sample.method)
                .bind("route", sample.route)
                .bind("status", sample.status)
                .bind("duration", sample.durationMs)
                .bind("ts", sample.timestamp),
        ).getOrThrow()
    }
}
