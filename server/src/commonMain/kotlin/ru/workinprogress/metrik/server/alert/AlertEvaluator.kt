package ru.workinprogress.metrik.server.alert

import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asDouble
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLongOrNull
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import ru.workinprogress.metrik.api.AlertRuleView
import ru.workinprogress.metrik.server.query.AdminService
import ru.workinprogress.metrik.wire.Histogram
import ru.workinprogress.metrik.wire.MetrikJson
import ru.workinprogress.metrik.wire.isServerError

private const val MINUTE_MS = 60_000L

/** Что правило думает про сервис прямо сейчас. */
class RuleVerdict(
    val ruleId: String,
    val breached: Boolean,
    val detail: String,
)

/**
 * Проверка правил по последним окнам.
 *
 * Пороги по количеству и гистерезис живут здесь не для красоты: без `minCount` один ошибочный
 * запрос из трёх даёт 33 % и будит дежурного, а `absent` без счёта по сервису срабатывал бы на
 * каждый rolling update.
 */
class AlertEvaluator(
    private val db: ISQLite,
    private val admin: AdminService,
) {
    suspend fun evaluate(
        serviceId: Long,
        nowMs: Long,
    ): List<RuleVerdict> {
        val rules = admin.rules(serviceId).filter { it.enabled }.associateBy { it.ruleId }

        return listOfNotNull(
            rules[AlertRuleIds.ERROR_RATE]?.let { errorRate(serviceId, nowMs, it) },
            rules[AlertRuleIds.LATENCY]?.let { latency(serviceId, nowMs, it) },
            rules[AlertRuleIds.MEMORY]?.let { memory(serviceId, nowMs, it) },
            rules[AlertRuleIds.ABSENT]?.let { absent(serviceId, nowMs, it) },
        )
    }

    private suspend fun errorRate(
        serviceId: Long,
        nowMs: Long,
        rule: AlertRuleView,
    ): RuleVerdict {
        val from = nowMs - 2 * MINUTE_MS
        val rows =
            db
                .fetchAll(
                    Statement
                        .create(
                            """
                            SELECT status, count FROM route_windows
                            WHERE service_id = :id AND window_start >= :from
                            """.trimIndent(),
                        ).bind("id", serviceId)
                        .bind("from", from),
                ).getOrThrow()
                .rows

        val total = rows.sumOf { it.get("count").asLong() }
        val errors =
            rows
                .filter { isServerError(it.get("status").asLong().toInt()) }
                .sumOf { it.get("count").asLong() }

        // Защита от малых чисел: 1 ошибка из 3 это не инцидент, это статистический шум.
        if (total < rule.minCount) {
            return RuleVerdict(rule.ruleId, breached = false, detail = "too few requests ($total)")
        }

        val ratio = errors.toDouble() / total
        return RuleVerdict(
            ruleId = rule.ruleId,
            breached = ratio > rule.threshold,
            detail = "${(ratio * 100).toInt()}% of $total requests failed",
        )
    }

    private suspend fun latency(
        serviceId: Long,
        nowMs: Long,
        rule: AlertRuleView,
    ): RuleVerdict {
        val from = nowMs - 2 * MINUTE_MS
        val histogram = Histogram()

        db
            .fetchAll(
                Statement
                    .create(
                        """
                        SELECT buckets FROM route_windows
                        WHERE service_id = :id AND window_start >= :from
                        """.trimIndent(),
                    ).bind("id", serviceId)
                    .bind("from", from),
            ).getOrThrow()
            .rows
            .forEach { row ->
                histogram.merge(Histogram.fromSparse(MetrikJson.decodeFromString(row.get("buckets").asString())))
            }

        if (histogram.totalCount == 0L) {
            return RuleVerdict(rule.ruleId, breached = false, detail = "no traffic")
        }

        val p95 = histogram.percentileMs(0.95)
        return RuleVerdict(
            ruleId = rule.ruleId,
            breached = p95 > rule.threshold,
            detail = "p95 ≈ ${p95.toInt()}ms",
        )
    }

    private suspend fun memory(
        serviceId: Long,
        nowMs: Long,
        rule: AlertRuleView,
    ): RuleVerdict {
        val from = nowMs - 2 * MINUTE_MS
        val row =
            db
                .fetchAll(
                    Statement
                        .create(
                            """
                            SELECT MAX(CAST(s.heap_used AS REAL) / s.heap_max) AS ratio
                            FROM system_windows s
                            JOIN instances i ON i.id = s.instance_id
                            WHERE i.service_id = :id AND s.window_start >= :from AND s.heap_max IS NOT NULL
                            """.trimIndent(),
                        ).bind("id", serviceId)
                        .bind("from", from),
                ).getOrThrow()
                .rows
                .firstOrNull()

        val ratio = row?.get("ratio")?.asDoubleOrNull()

        // Нет лимита памяти (нативный процесс без cgroup) — правило молчит, а не считает ноль.
        if (ratio == null) return RuleVerdict(rule.ruleId, breached = false, detail = "no memory limit reported")

        return RuleVerdict(
            ruleId = rule.ruleId,
            breached = ratio > rule.threshold,
            detail = "heap at ${(ratio * 100).toInt()}% of limit",
        )
    }

    private suspend fun absent(
        serviceId: Long,
        nowMs: Long,
        rule: AlertRuleView,
    ): RuleVerdict {
        val lastSeen =
            db
                .fetchAll(
                    Statement
                        .create("SELECT MAX(last_seen) AS seen FROM instances WHERE service_id = :id")
                        .bind("id", serviceId),
                ).getOrThrow()
                .rows
                .firstOrNull()
                ?.get("seen")
                ?.asLongOrNull()

        val silenceMs = if (lastSeen == null) Long.MAX_VALUE else nowMs - lastSeen
        val thresholdMs = (rule.threshold * MINUTE_MS).toLong()

        // Текст честный: метриками нельзя отличить «сервис упал» от «сеть до metrik оборвалась».
        return RuleVerdict(
            ruleId = rule.ruleId,
            breached = silenceMs > thresholdMs,
            detail = "no data for ${silenceMs / MINUTE_MS} min",
        )
    }
}

private fun io.github.smyrgeorge.sqlx4k.ResultSet.Row.Column.asDoubleOrNull(): Double? = asStringOrNull()?.toDoubleOrNull()
