package ru.workinprogress.metrik.cli

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.text.SpanStyle
import com.jakewharton.mosaic.text.buildAnnotatedString
import com.jakewharton.mosaic.text.withStyle
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import ru.workinprogress.metrik.api.isFiring

// Drawing. Every decision about *what* the numbers mean lives elsewhere; this file only decides
// where they go on screen.

/** Colour is a second channel, never the only one — see [chart] for the same rule in the plot. */
private fun colourOf(severity: Severity): Color =
    when (severity) {
        Severity.NORMAL -> Color.Green
        Severity.WARN -> Color.Yellow
        Severity.HIGH -> Color.Red
        Severity.GAP -> Color(0x80, 0x80, 0x80)
        Severity.CHROME -> Color.Unspecified
    }

@Composable
fun Line(
    cells: List<Cell>,
    colour: Boolean,
) {
    if (!colour) {
        Text(cells.plain())
        return
    }

    Text(
        buildAnnotatedString {
            var index = 0
            while (index < cells.size) {
                val severity = cells[index].severity
                val start = index
                while (index < cells.size && cells[index].severity == severity) index++

                val run = cells.subList(start, index).plain()
                val tint = colourOf(severity)
                if (tint == Color.Unspecified) append(run) else withStyle(SpanStyle(color = tint)) { append(run) }
            }
        },
    )
}

@Composable
fun ServicesScreen(
    state: UiState,
    config: CliConfig,
) {
    Column {
        Header(state)

        if (state.services.isEmpty() && !state.loading) {
            Text("no services are reporting")
        }

        Text("  " + column("service", 22) + column("rps", 8) + column("errors", 8) + column("p95", 9) + column("inst", 5) + "last seen")

        state.services.forEachIndexed { index, service ->
            val selected = index == state.selected
            val firing = service.firingAlerts.isNotEmpty()

            val row =
                (if (selected) "> " else "  ") +
                    column(service.name, 22) +
                    column(rate(service.requestsPerSecond), 8) +
                    column(percent(service.errorRate), 8) +
                    column(millis(service.p95Ms), 9) +
                    column(service.instances.toString(), 5) +
                    ago(service.lastSeenAt)

            // A firing service is red and marked; the mark is what survives a pipe.
            val severity = if (firing) Severity.HIGH else Severity.CHROME
            val suffix = if (firing) "  ! " + service.firingAlerts.joinToString(" ") else ""

            Line((row + suffix).map { Cell(it, severity) }, config.colour)
        }

        if (state.alerts.isNotEmpty()) {
            Text("")
            Text("firing:")
            state.alerts.filter { it.isFiring }.forEach { alert ->
                val muted = alert.mutedUntil?.let { " (muted, still firing)" }.orEmpty()
                Line(
                    ("  " + column(alert.service, 22) + column(alert.ruleId, 14) + ago(alert.since) + muted)
                        .map { Cell(it, Severity.HIGH) },
                    config.colour,
                )
            }
        }

        Footer("↑↓ move   enter open   r refresh   q quit")
    }
}

@Composable
fun DetailScreen(
    state: UiState,
    config: CliConfig,
    width: Int,
) {
    val detail = state.detail

    Column {
        Header(state)

        if (detail == null) {
            Text("loading…")
            Footer("esc back   q quit")
            return@Column
        }

        val o = detail.overview
        Text(detail.service)
        Text(
            "  requests " + o.requests + "   errors " + o.errors + " (" + percent(o.errorRate) + ")" +
                "   p50 " + millis(o.p50Ms) + "   p95 " + millis(o.p95Ms) + "   max " + millis(o.maxMs.toDouble()),
        )
        Text("")

        val glyphs = if (config.unicode) Glyphs.UNICODE else Glyphs.ASCII
        val plot = (width - 10).coerceIn(10, 200)

        // Laid on the grid that was asked for, then squeezed to the terminal. Both steps preserve
        // gaps: a minute nobody reported and an incomplete window are absences, not zeroes.
        val values = fit(align(detail.series, detail.from, detail.to), plot)

        Text(
            "p95, " +
                detail.series.step.name
                    .lowercase() + " steps",
        )
        chart(values, height = 7, glyphs = glyphs, thresholds = Thresholds(warn = 300.0, high = 1000.0), unit = "ms")
            .forEach { row -> Line(row, config.colour) }

        val axis = timeAxis(clock(detail.from), clock(detail.to), values.size)
        if (axis.isNotEmpty()) Line(axis, config.colour)

        val marks = deployColumns(detail.deploys, detail.from, detail.to, detail.series.step, values.size)
        if (marks.isNotEmpty()) markers(marks, values.size, glyphs).forEach { row -> Line(row, config.colour) }

        Text("")
        Text("slowest routes")
        if (detail.slow.isEmpty()) Text("  no traffic in this window")
        detail.slow.take(8).forEach { route ->
            Text(
                "  " + column(route.method, 7) + column(route.route, 34) +
                    column(route.status.toString(), 6) + column(route.count.toString(), 8) +
                    column(millis(route.p95Ms), 9) + millis(route.maxMs.toDouble()),
            )
        }

        if (detail.errors.isNotEmpty()) {
            Text("")
            Text("server errors")
            detail.errors.take(5).forEach { route ->
                Line(
                    (
                        "  " + column(route.method, 7) + column(route.route, 34) +
                            column(route.status.toString(), 6) + route.count.toString()
                    ).map { Cell(it, Severity.HIGH) },
                    config.colour,
                )
            }
        }

        Footer("esc back   r refresh   q quit")
    }
}

@Composable
private fun Header(state: UiState) {
    val status =
        when {
            state.failure != null -> "  offline: " + state.failure + " — showing last known numbers"
            state.loading -> "  refreshing…"
            else -> ""
        }

    Text("metrik" + status)
    Text("")
}

@Composable
private fun Footer(keys: String) {
    Text("")
    Text(keys)
}
