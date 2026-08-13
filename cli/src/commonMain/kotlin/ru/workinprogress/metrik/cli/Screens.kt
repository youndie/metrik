package ru.workinprogress.metrik.cli

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.layout.fillMaxSize
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.text.SpanStyle
import com.jakewharton.mosaic.text.buildAnnotatedString
import com.jakewharton.mosaic.text.withStyle
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import ru.workinprogress.metrik.api.isFiring

// Экраны собираются как список строк, а рисуются одним местом. Так кадр можно обрезать по размеру
// терминала (см. Viewport) и проверить тестом — в отличие от композаблов, разбросанных по файлу.

/** Colour is a second channel, never the only one — see [chart] for the same rule in the plot. */
private fun colourOf(severity: Severity): Color =
    when (severity) {
        Severity.NORMAL -> Color.Green
        Severity.WARN -> Color.Yellow
        Severity.HIGH -> Color.Red
        Severity.GAP -> Color(0x80, 0x80, 0x80)
        Severity.CHROME -> Color.Unspecified
    }

/** Строка кадра из обычного текста. */
fun row(
    text: String,
    severity: Severity = Severity.CHROME,
): List<Cell> = text.map { Cell(it, severity) }

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

/**
 * Кадр во весь терминал: содержимое сверху, подсказка по клавишам прижата к низу.
 *
 * Содержимое обрезается по обеим осям — иначе кадр выше экрана ломает перерисовку Mosaic и
 * оставляет куски прошлого кадра поверх нового.
 */
@Composable
fun Screen(
    rows: List<List<Cell>>,
    footer: String,
    config: CliConfig,
    width: Int,
    height: Int,
    keepVisible: Int = 0,
) {
    // Две строки внизу — пустая и подсказка; всё остальное отдано содержимому.
    val body = (height - 2).coerceAtLeast(1)

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f)) {
            rows.clamp(width, body, keepVisible).forEach { line -> Line(line, config.colour) }
        }
        Text("")
        Text(footer.take(width))
    }
}

/** Шапка: что за приложение и что с ним сейчас происходит. */
private fun header(state: UiState): List<List<Cell>> {
    val status =
        when {
            state.failure != null -> "  offline: " + state.failure + " — showing last known numbers"
            state.loading -> "  refreshing…"
            else -> ""
        }

    return listOf(row("metrik$status"), row(""))
}

/** Строки экрана со списком сервисов и индекс выбранной строки — её нельзя увести за край. */
fun servicesRows(state: UiState): Pair<List<List<Cell>>, Int> {
    val rows = mutableListOf<List<Cell>>()
    rows += header(state)

    if (state.services.isEmpty() && !state.loading) rows += row("no services are reporting")

    rows +=
        row(
            "  " + column("service", 22) + column("rps", 8) + column("errors", 8) +
                column("p95", 9) + column("inst", 5) + "last seen",
        )

    val selectedRow = rows.size + state.selected

    state.services.forEachIndexed { index, service ->
        val firing = service.firingAlerts.isNotEmpty()
        val line =
            (if (index == state.selected) "> " else "  ") +
                column(service.name, 22) +
                column(rate(service.requestsPerSecond), 8) +
                column(percent(service.errorRate), 8) +
                column(millis(service.p95Ms), 9) +
                column(service.instances.toString(), 5) +
                ago(service.lastSeenAt) +
                // Пометка `!` — то, что переживёт пайп и монохромный терминал, в отличие от цвета.
                if (firing) "  ! " + service.firingAlerts.joinToString(" ") else ""

        rows += row(line, if (firing) Severity.HIGH else Severity.CHROME)
    }

    val firing = state.alerts.filter { it.isFiring }
    if (firing.isNotEmpty()) {
        rows += row("")
        rows += row("firing:")
        firing.forEach { alert ->
            val muted = alert.mutedUntil?.let { " (muted, still firing)" }.orEmpty()
            rows += row("  " + column(alert.service, 22) + column(alert.ruleId, 14) + ago(alert.since) + muted, Severity.HIGH)
        }
    }

    return rows to selectedRow
}

/** Строки карточки сервиса. [width] нужен графику: он рисуется по колонке на точку. */
fun detailRows(
    state: UiState,
    config: CliConfig,
    width: Int,
): List<List<Cell>> {
    val rows = mutableListOf<List<Cell>>()
    rows += header(state)

    val detail = state.detail ?: return rows + listOf(row("loading…"))

    val overview = detail.overview
    rows += row(detail.service)
    rows +=
        row(
            "  requests " + overview.requests + "   errors " + overview.errors +
                " (" + percent(overview.errorRate) + ")   p50 " + millis(overview.p50Ms) +
                "   p95 " + millis(overview.p95Ms) + "   max " + millis(overview.maxMs.toDouble()),
        )
    rows += row("")

    val glyphs = if (config.unicode) Glyphs.UNICODE else Glyphs.ASCII
    val plot = (width - 10).coerceIn(10, 200)

    // Ряд кладётся на сетку запрошенного окна, потом сжимается под терминал. Оба шага сохраняют
    // разрывы: минута без отчёта и неполное окно — это отсутствие, а не ноль.
    val values = fit(align(detail.series, detail.from, detail.to), plot)

    rows +=
        row(
            "p95, " +
                detail.series.step.name
                    .lowercase() + " steps",
        )
    rows += chart(values, height = 7, glyphs = glyphs, thresholds = detail.thresholds, unit = "ms")

    val axis = timeAxis(clock(detail.from), clock(detail.to), values.size)
    if (axis.isNotEmpty()) rows += axis

    val marks = deployColumns(detail.deploys, detail.from, detail.to, detail.series.step, values.size)
    if (marks.isNotEmpty()) rows += markers(marks, values.size, glyphs)

    rows += row("")
    rows += row("slowest routes")
    if (detail.slow.isEmpty()) rows += row("  no traffic in this window")
    detail.slow.take(8).forEach { route ->
        rows +=
            row(
                "  " + column(route.method, 7) + column(route.route, 34) +
                    column(route.status.toString(), 6) + column(route.count.toString(), 8) +
                    column(millis(route.p95Ms), 9) + millis(route.maxMs.toDouble()),
            )
    }

    if (detail.errors.isNotEmpty()) {
        rows += row("")
        rows += row("server errors")
        detail.errors.take(5).forEach { route ->
            rows +=
                row(
                    "  " + column(route.method, 7) + column(route.route, 34) +
                        column(route.status.toString(), 6) + route.count.toString(),
                    Severity.HIGH,
                )
        }
    }

    return rows
}
