package ru.workinprogress.metrik.cli

import ru.workinprogress.metrik.api.DeployMarker
import ru.workinprogress.metrik.api.Step
import ru.workinprogress.metrik.api.TimePoint
import ru.workinprogress.metrik.api.TimeSeries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val MINUTE = 60_000L

/**
 * The server sends points only for windows that reported, so an outage arrives as a shorter list
 * rather than a list with a hole. Everything here is about not drawing that as a smooth line.
 */
class SeriesTest {
    private fun point(
        at: Long,
        p95: Double,
        partial: Boolean = false,
    ) = TimePoint(at = at, requestsPerSecond = 1.0, errorRate = 0.0, p50Ms = p95 / 2, p95Ms = p95, maxMs = p95.toLong(), partial = partial)

    @Test
    fun `minutes nobody reported become gaps`() {
        // Given a service that went quiet for two minutes in the middle
        val from = 10 * MINUTE
        val to = from + 5 * MINUTE
        val series =
            TimeSeries(
                step = Step.MINUTE,
                points =
                    listOf(
                        point(from, 100.0),
                        point(from + MINUTE, 110.0),
                        point(from + 4 * MINUTE, 120.0),
                    ),
            )

        // When
        val values = align(series, from, to)

        // Then the silence is visible instead of being closed up
        assertEquals(5, values.size)
        assertEquals(100.0, values[0])
        assertEquals(110.0, values[1])
        assertNull(values[2])
        assertNull(values[3])
        assertEquals(120.0, values[4])
    }

    @Test
    fun `an incomplete window is a gap and not a measurement`() {
        val from = 0L
        val series = TimeSeries(step = Step.MINUTE, points = listOf(point(0, 100.0, partial = true)))

        assertNull(align(series, from, MINUTE).single())
    }

    @Test
    fun `a service that reported nothing at all is all gaps`() {
        val values = align(TimeSeries(step = Step.MINUTE, points = emptyList()), 0, 3 * MINUTE)

        assertEquals(3, values.size)
        assertTrue(values.all { it == null })
    }

    @Test
    fun `the hour step lays out hour buckets`() {
        val hour = 3_600_000L
        val series = TimeSeries(step = Step.HOUR, points = listOf(point(0, 50.0)))

        val values = align(series, 0, 3 * hour)

        assertEquals(3, values.size)
        assertEquals(50.0, values[0])
    }

    @Test
    fun `a deploy lands in the column of the minute it happened`() {
        val from = 0L
        val to = 10 * MINUTE

        val marks = deployColumns(listOf(DeployMarker("1.4.212", at = 5 * MINUTE)), from, to, Step.MINUTE, columns = 10)

        // Half way through the window, so half way across the chart
        assertEquals(mapOf(4 to "1.4.212"), marks)
    }

    @Test
    fun `a deploy outside the window is not drawn`() {
        val marks = deployColumns(listOf(DeployMarker("old", at = -MINUTE)), 0, 10 * MINUTE, Step.MINUTE, columns = 10)

        assertTrue(marks.isEmpty())
    }
}
