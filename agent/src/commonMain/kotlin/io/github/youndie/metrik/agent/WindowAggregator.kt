package io.github.youndie.metrik.agent

import io.github.youndie.metrik.wire.Histogram
import io.github.youndie.metrik.wire.RouteSeries
import io.github.youndie.metrik.wire.SlowSample
import io.github.youndie.metrik.wire.statusClassOf

/** Маршрут, который не сматчился ни на один роут: иначе каждый несуществующий путь дал бы серию. */
public const val ROUTE_UNMATCHED: String = "<unmatched>"

/** Куда сваливается всё, что не влезло в лимит кардинальности. */
public const val ROUTE_OTHER: String = "<other>"

private const val METHOD_OTHER = "*"

private class SeriesKey(
    val method: String,
    val route: String,
    val status: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is SeriesKey && other.method == method && other.route == route && other.status == status

    override fun hashCode(): Int = (method.hashCode() * 31 + route.hashCode()) * 31 + status
}

private class SeriesAccumulator {
    var count: Int = 0
    var sumMs: Long = 0
    var maxMs: Int = 0
    val histogram = Histogram()

    fun record(durationMs: Long) {
        count++
        sumMs += durationMs
        if (durationMs > maxMs) maxMs = durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        histogram.record(durationMs)
    }
}

/** Содержимое закрытого окна. */
public class WindowData(
    public val routes: List<RouteSeries>,
    public val slow: List<SlowSample>,
    /** Сколько запросов ушло в [ROUTE_OTHER] из-за лимита кардинальности. */
    public val collapsed: Int,
)

/**
 * Накопитель окна.
 *
 * **Не потокобезопасен намеренно:** его крутит одна корутина-потребитель, а вызовы с горячего пути
 * доезжают через канал. Так на замере запроса нет ни локов, ни contention, а здесь — ни атомиков,
 * ни синхронизации.
 */
public class WindowAggregator(
    private val maxSeries: Int = 200,
    private val slowSampleLimit: Int = 5,
) {
    private val series = HashMap<SeriesKey, SeriesAccumulator>()
    private val slow = ArrayList<SlowSample>()
    private var collapsed = 0

    public fun record(
        method: String,
        route: String,
        status: Int,
        durationMs: Long,
        timestampMs: Long,
    ) {
        val key = SeriesKey(method, route, status)
        val existing = series[key]

        if (existing != null) {
            existing.record(durationMs)
        } else if (series.size < maxSeries) {
            series[key] = SeriesAccumulator().also { it.record(durationMs) }
        } else {
            // Лимит исчерпан: схлопываем в одну серию на класс статуса, чтобы память агента
            // не росла от чужой кардинальности. Ошибки при этом остаются различимы.
            collapsed++
            val fallback = SeriesKey(METHOD_OTHER, ROUTE_OTHER, statusClassOf(status))
            series.getOrPut(fallback) { SeriesAccumulator() }.record(durationMs)
        }

        recordSlow(method, route, status, durationMs, timestampMs)
    }

    private fun recordSlow(
        method: String,
        route: String,
        status: Int,
        durationMs: Long,
        timestampMs: Long,
    ) {
        if (slowSampleLimit <= 0) return
        if (slow.size == slowSampleLimit && durationMs <= slow.last().durationMs) return

        val sample =
            SlowSample(
                method = method,
                route = route,
                status = status,
                durationMs = durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                timestamp = timestampMs,
            )

        slow += sample
        slow.sortByDescending { it.durationMs }
        while (slow.size > slowSampleLimit) slow.removeAt(slow.lastIndex)
    }

    /** Забирает накопленное и обнуляет состояние под следующее окно. */
    public fun drain(): WindowData {
        val routes =
            series.map { (key, accumulator) ->
                RouteSeries(
                    method = key.method,
                    route = key.route,
                    status = key.status,
                    count = accumulator.count,
                    sumMs = accumulator.sumMs,
                    maxMs = accumulator.maxMs,
                    buckets = accumulator.histogram,
                )
            }

        val data = WindowData(routes = routes, slow = slow.toList(), collapsed = collapsed)

        series.clear()
        slow.clear()
        collapsed = 0

        return data
    }
}
