package ru.workinprogress.metrik.cli

import ru.workinprogress.metrik.api.Step
import ru.workinprogress.metrik.api.TimeSeries

/**
 * Lays a series onto a regular grid of steps, so silence looks like silence.
 *
 * The server returns a point only for windows that reported: a service that went quiet for twenty
 * minutes comes back as a *shorter* series, not as one with a hole in it. Drawn as-is, those twenty
 * minutes vanish and the line runs smoothly across the outage — the exact confusion between "less
 * data" and "less traffic" that the `partial` flag exists to prevent, arriving through a different
 * door.
 *
 * So the client rebuilds the grid it asked for and marks every step nobody reported as a gap.
 * `partial` points stay gaps too: an incomplete window is not a measurement.
 *
 * The web dashboard has the same blind spot; fixing it there means changing what the API returns,
 * which is a bigger decision than this client gets to make on its own (see the backlog).
 */
fun align(
    series: TimeSeries,
    from: Long,
    to: Long,
    value: (ru.workinprogress.metrik.api.TimePoint) -> Double = { it.p95Ms },
): List<Double?> {
    val stepMs = series.step.durationMs()
    if (stepMs <= 0 || to <= from) return emptyList()

    val start = from - from % stepMs
    val byBucket = series.points.associateBy { point -> point.at - point.at % stepMs }

    val buckets = ((to - start) / stepMs).toInt().coerceIn(1, MAX_BUCKETS)

    return (0 until buckets).map { index ->
        val point = byBucket[start + index * stepMs]
        if (point == null || point.partial) null else value(point)
    }
}

/** Deploy marks against the same grid, so a release stays under the moment it happened. */
fun deployColumns(
    deploys: List<ru.workinprogress.metrik.api.DeployMarker>,
    from: Long,
    to: Long,
    step: Step,
    columns: Int,
): Map<Int, String> {
    if (deploys.isEmpty() || columns <= 0 || to <= from) return emptyMap()

    val stepMs = step.durationMs()
    val start = from - from % stepMs
    val span = (to - start).coerceAtLeast(1)

    return deploys
        .filter { it.at in start..to }
        .associate { deploy ->
            val ratio = (deploy.at - start).toDouble() / span
            (ratio * (columns - 1)).toInt().coerceIn(0, columns - 1) to deploy.release
        }
}

/**
 * Пороги графика p95 — из правила `latency` этого сервиса.
 *
 * `high` — сам порог: столбик, дотянувшийся до него, и есть тот, на котором правило сработало бы.
 * `warn` — три четверти порога: «ещё не авария, но уже близко». Это единственная выдуманная здесь
 * величина, и она выдумана только для промежуточного цвета, а не для пунктира.
 *
 * Выключенное правило порогов не даёт: раскрашивать по порогу, который никогда не сработает,
 * значит рисовать тревогу там, где её не будет.
 */
fun latencyThresholds(rules: List<ru.workinprogress.metrik.api.AlertRuleView>): Thresholds {
    val latency = rules.firstOrNull { it.ruleId == "latency" && it.enabled } ?: return Thresholds()

    return Thresholds(warn = latency.threshold * 0.75, high = latency.threshold)
}

/** A grid wider than any terminal is pointless, and an unbounded one is a way to run out of memory. */
private const val MAX_BUCKETS = 10_000

internal fun Step.durationMs(): Long =
    when (this) {
        Step.MINUTE -> 60_000L
        Step.HOUR -> 3_600_000L
        Step.DAY -> 86_400_000L
    }
