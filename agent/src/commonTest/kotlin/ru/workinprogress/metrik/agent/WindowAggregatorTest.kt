package ru.workinprogress.metrik.agent

import ru.workinprogress.metrik.wire.encodeStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowAggregatorTest {
    @Test
    fun `requests to the same route should collapse into one series`() {
        // Given
        val aggregator = WindowAggregator()

        // When — сто разных id, один шаблон.
        repeat(100) { aggregator.record("GET", "/users/{id}", encodeStatus(200), 5, 0) }

        // Then
        val series = aggregator.drain().routes.single()
        assertEquals(100, series.count)
        assertEquals(500, series.sumMs)
    }

    @Test
    fun `different statuses of one route should stay separate series`() {
        // Given
        val aggregator = WindowAggregator()

        // When
        aggregator.record("GET", "/users/{id}", encodeStatus(200), 5, 0)
        aggregator.record("GET", "/users/{id}", encodeStatus(404), 5, 0)
        aggregator.record("GET", "/users/{id}", encodeStatus(401), 5, 0)

        // Then — 401 и 404 это разные инциденты, сливать их нельзя.
        val statuses =
            aggregator
                .drain()
                .routes
                .map { it.status }
                .sorted()
        assertEquals(listOf(2, 401, 404), statuses)
    }

    @Test
    fun `cardinality beyond the limit should collapse instead of growing memory`() {
        // Given — лимит маленький, чтобы поведение было видно.
        val aggregator = WindowAggregator(maxSeries = 3)

        // When
        repeat(50) { index -> aggregator.record("GET", "/route/$index", encodeStatus(200), 5, 0) }

        // Then
        val data = aggregator.drain()
        assertTrue(data.routes.size <= 4, "series grew past the limit: ${data.routes.size}")
        assertTrue(data.collapsed > 0)

        val other = data.routes.single { it.route == ROUTE_OTHER }
        assertEquals(data.collapsed, other.count)
    }

    @Test
    fun `collapsed series should keep the status class distinguishable`() {
        // Given
        val aggregator = WindowAggregator(maxSeries = 1)

        // When
        aggregator.record("GET", "/a", encodeStatus(200), 5, 0)
        aggregator.record("GET", "/b", encodeStatus(200), 5, 0)
        aggregator.record("GET", "/c", encodeStatus(503), 5, 0)

        // Then — ошибки не растворяются в общей куче успешных запросов.
        val collapsedStatuses =
            aggregator
                .drain()
                .routes
                .filter { it.route == ROUTE_OTHER }
                .map { it.status }
        assertEquals(listOf(2, 5), collapsedStatuses.sorted())
    }

    @Test
    fun `slow samples should keep the slowest requests only`() {
        // Given
        val aggregator = WindowAggregator(slowSampleLimit = 3)

        // When
        listOf(10L, 900L, 30L, 5000L, 70L, 2000L).forEach { duration ->
            aggregator.record("GET", "/x", encodeStatus(200), duration, duration)
        }

        // Then
        val slow = aggregator.drain().slow
        assertEquals(listOf(5000, 2000, 900), slow.map { it.durationMs })
    }

    @Test
    fun `draining should reset the window`() {
        // Given
        val aggregator = WindowAggregator()
        aggregator.record("GET", "/x", encodeStatus(200), 5, 0)
        aggregator.drain()

        // When
        val second = aggregator.drain()

        // Then — пустое окно это не то же самое, что прошлое окно ещё раз.
        assertTrue(second.routes.isEmpty())
        assertTrue(second.slow.isEmpty())
        assertEquals(0, second.collapsed)
    }

    @Test
    fun `histogram inside a series should match the recorded durations`() {
        // Given
        val aggregator = WindowAggregator()

        // When
        listOf(1L, 5L, 5L, 400L).forEach { aggregator.record("GET", "/x", encodeStatus(200), it, 0) }

        // Then
        val series = aggregator.drain().routes.single()
        assertEquals(4L, series.buckets.totalCount)
        assertEquals(400, series.maxMs)
    }
}
