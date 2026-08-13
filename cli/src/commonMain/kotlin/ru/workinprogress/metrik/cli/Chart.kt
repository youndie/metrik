package ru.workinprogress.metrik.cli

import kotlin.math.roundToInt

/**
 * Terminal charts for time series.
 *
 * The renderer is pure: it turns a series into a grid of [Cell]s and never touches the screen.
 * Colour is not decided here either — a cell carries a [Severity], and whoever draws it maps that
 * to a colour or ignores it. That keeps the whole thing testable and keeps colour optional rather
 * than load-bearing.
 *
 * **A `null` sample is a gap, not a zero.** In metrik a point built from an incomplete window is
 * flagged `partial`, and drawing it as a value would conflate "less data" with "less traffic".
 * The web dashboard breaks the line; here a gap gets its own glyph, because an empty column is
 * exactly what a zero looks like.
 */
enum class Severity {
    /** Below the warning threshold, or no threshold given. */
    NORMAL,

    /** Between the two thresholds. */
    WARN,

    /** At or above the alerting threshold — the same number that would fire a rule. */
    HIGH,

    /** No data for this window. */
    GAP,

    /** Frame, axis, labels. */
    CHROME,
}

data class Cell(
    val char: Char,
    val severity: Severity,
)

data class Glyphs(
    val bar: Char,
    val half: Char,
    val gap: Char,
    val guide: Char,
    val axis: Char,
    val corner: Char,
    val wall: Char,
    val marker: Char,
) {
    companion object {
        /** Box drawing plus a half block, which doubles the vertical resolution for free. */
        val UNICODE = Glyphs(bar = '█', half = '▄', gap = '┊', guide = '┈', axis = '─', corner = '┼', wall = '│', marker = '▲')

        /**
         * For terminals that render blocks and box drawing badly.
         *
         * There is no half step here, so the chart holds half the detail. That is the price of
         * the fallback, and the reason it is not the default.
         */
        val ASCII = Glyphs(bar = '#', half = '.', gap = ':', guide = '-', axis = '-', corner = '+', wall = '|', marker = '^')
    }
}

/**
 * Thresholds to colour against.
 *
 * They exist so colour means something: a red column is one that would fire an alert, not one the
 * palette happened to reach. Absent thresholds leave every column [Severity.NORMAL].
 */
data class Thresholds(
    val warn: Double? = null,
    val high: Double? = null,
)

private const val LABEL_WIDTH = 6

/**
 * Renders [values] as a column chart [height] rows tall.
 *
 * The series is drawn one column per sample; deciding how many samples fit belongs to the caller,
 * which is the only side that knows the terminal width.
 *
 * When [thresholds] carry a `high` value, a dashed guide is drawn at that level. That is on
 * purpose: it makes "above the alerting threshold" readable **without** colour, so the chart still
 * works when piped, on a monochrome terminal, or for a reader who cannot distinguish red.
 */
fun chart(
    values: List<Double?>,
    height: Int = 6,
    glyphs: Glyphs = Glyphs.UNICODE,
    thresholds: Thresholds = Thresholds(),
    unit: String = "",
): List<List<Cell>> {
    require(height > 0) { "chart height must be positive" }

    if (values.isEmpty()) return listOf(text("no data", Severity.CHROME))

    val top = values.filterNotNull().maxOrNull() ?: 0.0
    val steps = if (glyphs.half == '.') height else height * 2

    // Anything above zero gets at least one step. Without this a small-but-present sample rounds
    // away — 10ms next to a 400ms peak drew as blank, which is what zero and "nothing here" also
    // look like. Three different facts must not share one appearance.
    fun level(value: Double): Int =
        when {
            top <= 0.0 -> 0
            value <= 0.0 -> 0
            else -> (value / top * steps).roundToInt().coerceIn(1, steps)
        }

    fun severityOf(value: Double): Severity =
        when {
            thresholds.high != null && value >= thresholds.high -> Severity.HIGH
            thresholds.warn != null && value >= thresholds.warn -> Severity.WARN
            else -> Severity.NORMAL
        }

    val guideRow = thresholds.high?.let { if (top <= 0.0) null else ((it / top) * height).roundToInt().coerceIn(1, height) }

    val rows =
        (height downTo 1).map { row ->
            val label = if (row == height) format(top) + unit else ""
            val head = text(label.padStart(LABEL_WIDTH) + " " + glyphs.wall, Severity.CHROME)

            val body =
                values.map { value ->
                    when (value) {
                        null -> {
                            Cell(glyphs.gap, Severity.GAP)
                        }

                        else -> {
                            val filled = level(value)
                            val full = if (glyphs.half == '.') row else row * 2
                            when {
                                filled >= full -> Cell(glyphs.bar, severityOf(value))
                                glyphs.half != '.' && filled >= full - 1 && filled > 0 -> Cell(glyphs.half, severityOf(value))
                                row == guideRow -> Cell(glyphs.guide, Severity.CHROME)
                                else -> Cell(' ', Severity.CHROME)
                            }
                        }
                    }
                }

            head + body
        }

    val axis = text("0".padStart(LABEL_WIDTH) + " " + glyphs.corner + glyphs.axis.toString().repeat(values.size), Severity.CHROME)

    return rows + listOf(axis)
}

/**
 * The deploy row.
 *
 * A marker sits directly under its own sample. A chart where the release is drawn next to, rather
 * than at, the moment it happened answers "did the deploy break it" incorrectly.
 */
fun markers(
    positions: Map<Int, String>,
    width: Int,
    glyphs: Glyphs = Glyphs.UNICODE,
): List<List<Cell>> {
    if (positions.isEmpty()) return emptyList()

    val row = CharArray(width) { ' ' }
    positions.keys.filter { it in 0 until width }.forEach { index -> row[index] = glyphs.marker }

    val legend = positions.entries.sortedBy { it.key }.joinToString(", ") { (_, release) -> release }

    // The legend lives in the label gutter. Starting it with the marker glyph in column zero read
    // as another release at the beginning of the series.
    return listOf(
        text(" ".repeat(LABEL_WIDTH + 2), Severity.CHROME) + row.map { Cell(it, Severity.CHROME) },
        text("deploy".padStart(LABEL_WIDTH) + "  " + legend, Severity.CHROME),
    )
}

/** Flattens a rendered row for logs, tests and pipes — everything colour cannot survive. */
fun List<Cell>.plain(): String = map { it.char }.joinToString("")

/**
 * Fits a series into [width] columns by averaging neighbours.
 *
 * Sampling — keeping every n-th point — would drop the very spike the chart exists to show.
 * Averaging keeps it and flattens it instead, which is the lesser lie of the two.
 *
 * **A bucket containing a gap stays a gap.** Averaging around missing data would invent a value for
 * a window that never reported — exactly what the `partial` flag exists to prevent.
 */
fun fit(
    values: List<Double?>,
    width: Int,
): List<Double?> {
    if (width <= 0 || values.isEmpty() || values.size <= width) return values

    val bucket = values.size.toDouble() / width

    return (0 until width).map { index ->
        val from = (index * bucket).toInt()
        val to = ((index + 1) * bucket).toInt().coerceAtLeast(from + 1).coerceAtMost(values.size)
        val slice = values.subList(from, to)

        if (slice.any { it == null }) null else slice.filterNotNull().average()
    }
}

/**
 * Time labels under the axis — the ends only.
 *
 * Evenly spaced labels look tidier and start lying about which column they belong to as soon as the
 * series has been fitted into the terminal.
 */
fun timeAxis(
    from: String,
    to: String,
    width: Int,
): List<Cell> {
    if (width < from.length + to.length + 1) return emptyList()

    val line = " ".repeat(LABEL_WIDTH + 2) + from + " ".repeat(width - from.length - to.length) + to

    return line.map { Cell(it, Severity.CHROME) }
}

private fun text(
    value: String,
    severity: Severity,
): List<Cell> = value.map { Cell(it, severity) }

/** Compact numbers: a chart as wide as the terminal will not survive "412.6666666666667". */
private fun format(value: Double): String =
    when {
        value >= 1000 -> (value / 1000).roundToInt().toString() + "k"
        value >= 10 -> value.roundToInt().toString()
        value > 0 -> ((value * 10).roundToInt() / 10.0).toString()
        else -> "0"
    }
