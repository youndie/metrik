package ru.workinprogress.metrik.cli

import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

// Text formatting shared by every screen.
//
// Kept apart from the drawing so it can be tested: the difference between "0%" and "0.04%" error
// rate is the difference between a quiet service and a broken one, and rounding it away inside a
// composable is impossible to notice.

/** Percentages with enough precision to distinguish "none" from "few". */
fun percent(rate: Double): String =
    when {
        rate <= 0.0 -> "0%"
        rate < 0.001 -> "<0.1%"
        rate < 0.1 -> ((rate * 1000).roundToInt() / 10.0).toString() + "%"
        else -> (rate * 100).roundToInt().toString() + "%"
    }

fun millis(value: Double): String =
    when {
        value >= 1000 -> ((value / 100).roundToInt() / 10.0).toString() + "s"
        value >= 10 -> value.roundToInt().toString() + "ms"
        value > 0 -> ((value * 10).roundToInt() / 10.0).toString() + "ms"
        else -> "0ms"
    }

fun rate(value: Double): String =
    when {
        value >= 100 -> value.roundToInt().toString()
        value >= 1 -> ((value * 10).roundToInt() / 10.0).toString()
        value > 0 -> ((value * 100).roundToInt() / 100.0).toString()
        else -> "0"
    }

@OptIn(ExperimentalTime::class)
fun clock(epochMillis: Long): String {
    val time = Instant.fromEpochMilliseconds(epochMillis)
    val seconds = time.epochSeconds
    val hours = ((seconds / 3600) % 24).toString().padStart(2, '0')
    val minutes = ((seconds / 60) % 60).toString().padStart(2, '0')

    return "$hours:$minutes"
}

/**
 * How long ago, in words.
 *
 * "last seen 4m ago" answers the question a timestamp only hints at, and the question here is
 * always the same: is this service still reporting.
 */
@OptIn(ExperimentalTime::class)
fun ago(
    epochMillis: Long?,
    now: Long = Clock.System.now().toEpochMilliseconds(),
): String {
    if (epochMillis == null) return "never"

    val seconds = (now - epochMillis) / 1000

    return when {
        seconds < 0 -> "clock skew"
        seconds < 90 -> "${seconds}s ago"
        seconds < 90 * 60 -> "${seconds / 60}m ago"
        seconds < 48 * 3600 -> "${seconds / 3600}h ago"
        else -> "${seconds / 86400}d ago"
    }
}

/** Pads or truncates to an exact width so columns line up regardless of the data. */
fun column(
    value: String,
    width: Int,
): String =
    when {
        value.length == width -> value

        value.length < width -> value.padEnd(width)

        // An ellipsis rather than a hard cut: a silently shortened route name reads as a different
        // route, and routes are what the user is about to go and grep for.
        width <= 1 -> value.take(width)

        else -> value.take(width - 1) + "…"
    }
