package ru.workinprogress.metrik.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Where the plotting area starts: label gutter plus the wall. */
private const val FIELD = 8

/**
 * The chart's promise is that it does not lie about what was not there.
 *
 * Terminal output invites being checked by eye on a screenshot, and that is exactly how a missing
 * window ends up looking like a drop in traffic.
 */
class ChartTest {
    private fun field(rows: List<List<Cell>>): List<List<Cell>> = rows.dropLast(1).map { it.drop(FIELD) }

    @Test
    fun `a gap is drawn as a gap and never as a zero`() {
        // Given a series with an incomplete window in the middle
        val values = listOf(10.0, 20.0, null, 20.0)

        // When
        val rows = field(chart(values, height = 4))

        // Then the gap column is marked on every row, and a real sample never is
        rows.forEach { row -> assertEquals(Severity.GAP, row[2].severity) }
        assertTrue(rows.none { it[0].severity == Severity.GAP })
    }

    @Test
    fun `a zero does not look like missing data`() {
        // Given zero is a measurement, absence is not
        val values = listOf(0.0, 100.0, null)

        // When
        val rows = field(chart(values, height = 4))

        // Then
        assertTrue(rows.all { it[0].char == ' ' })
        assertTrue(rows.all { it[2].char == Glyphs.UNICODE.gap })
    }

    @Test
    fun `the tallest sample reaches the top row`() {
        val rows = field(chart(listOf(1.0, 50.0, 100.0), height = 5))

        // Otherwise the maximum is labelled but never drawn
        assertEquals(Glyphs.UNICODE.bar, rows.first()[2].char)
    }

    @Test
    fun `a flat series is not drawn as empty`() {
        val rows = field(chart(listOf(7.0, 7.0, 7.0), height = 4))

        // "steady" and "nothing happened" have to look different
        assertTrue(rows.first().all { it.char == Glyphs.UNICODE.bar })
    }

    @Test
    fun `severity follows the alerting thresholds and nothing else`() {
        // Given the same numbers a rule would use
        val values = listOf(10.0, 200.0, 400.0)
        val rows = field(chart(values, height = 4, thresholds = Thresholds(warn = 150.0, high = 300.0)))

        // When looking at the bottom row, where every column has a bar
        val bottom = rows.last()

        // Then a red column is one that would have fired, not one the palette reached
        assertEquals(Severity.NORMAL, bottom[0].severity)
        assertEquals(Severity.WARN, bottom[1].severity)
        assertEquals(Severity.HIGH, bottom[2].severity)
    }

    @Test
    fun `without thresholds nothing is coloured as a problem`() {
        val rows = field(chart(listOf(1.0, 5000.0), height = 4))

        assertTrue(rows.flatten().none { it.severity == Severity.WARN || it.severity == Severity.HIGH })
    }

    @Test
    fun `the alerting threshold is readable without colour`() {
        // Given a threshold well below the peak
        val values = listOf(400.0, 10.0, 10.0)

        // When
        val rows = field(chart(values, height = 6, thresholds = Thresholds(high = 200.0)))

        // Then a guide is drawn, so the chart still works piped, monochrome, or for a reader who
        // cannot tell red from green
        assertTrue(rows.any { row -> row.any { it.char == Glyphs.UNICODE.guide } })
    }

    @Test
    fun `a deploy marker stands under its own sample`() {
        val marks = markers(mapOf(10 to "1.4.212"), width = 24).first().drop(FIELD).plain()

        // Shifting it turns the chart into a wrong answer to "did the deploy break it"
        assertEquals(10, marks.indexOf(Glyphs.UNICODE.marker))
        assertEquals(1, marks.count { it == Glyphs.UNICODE.marker })
    }

    @Test
    fun `the deploy legend does not start with a marker glyph`() {
        val legend = markers(mapOf(3 to "1.0.0"), width = 10).last()

        // A legend starting with the same glyph reads as a release at sample zero
        assertTrue(legend.drop(FIELD).plain().none { it == Glyphs.UNICODE.marker })
        assertTrue(legend.plain().contains("1.0.0"))
    }

    @Test
    fun `the fallback glyph set leaves no unicode anywhere`() {
        // Given the interface is English, so this can be a real promise rather than a half one
        val values = listOf(10.0, null, 300.0)

        // When
        val lines =
            chart(values, height = 3, glyphs = Glyphs.ASCII, thresholds = Thresholds(high = 200.0), unit = "ms") +
                markers(mapOf(0 to "1.0.0"), width = 3, glyphs = Glyphs.ASCII)

        // Then — including the wall, which was once left as unicode precisely because it had been
        // written into the code instead of into the glyph set
        lines.forEach { row ->
            assertTrue(row.plain().all { it.code < 128 }, "fallback must stay ascii: ${row.plain()}")
        }
    }

    @Test
    fun `an empty series says so rather than drawing an empty frame`() {
        assertEquals("no data", chart(emptyList()).single().plain())
    }

    @Test
    fun `fitting keeps the spike instead of sampling past it`() {
        // Given a quiet series with one bad minute in it
        val values = MutableList<Double?>(60) { 10.0 }.also { it[37] = 900.0 }

        // When squeezed into a terminal
        val fitted = fit(values, 12)

        // Then the spike survives — keeping every n-th point would have thrown away the one
        // sample worth looking at
        assertEquals(12, fitted.size)
        assertTrue(fitted.filterNotNull().max() > 10.0)
    }

    @Test
    fun `fitting never averages a gap away`() {
        // Given a missing window among real ones
        val values = listOf(10.0, 20.0, null, 40.0, 50.0, 60.0)

        // When
        val fitted = fit(values, 3)

        // Then the bucket that held it stays a gap: averaging around missing data would invent a
        // value for a window that never reported
        assertTrue(fitted.contains(null))
    }

    @Test
    fun `a series shorter than the terminal is left alone`() {
        val values = listOf<Double?>(1.0, 2.0, 3.0)

        assertEquals(values, fit(values, 10))
    }

    @Test
    fun `time labels sit at both ends of the axis`() {
        val line = timeAxis("12:00", "13:00", width = 24).plain()

        assertTrue(line.endsWith("13:00"))
        // The gutter keeps them under the plotting area, not under the value column
        assertEquals(FIELD, line.indexOf("12:00"))
    }

    @Test
    fun `time labels are dropped when they would not fit`() {
        assertTrue(timeAxis("12:00", "13:00", width = 4).isEmpty())
    }
}
