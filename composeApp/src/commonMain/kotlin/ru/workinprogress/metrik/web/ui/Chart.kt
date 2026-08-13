package ru.workinprogress.metrik.web.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.workinprogress.metrik.api.DeployMarker
import ru.workinprogress.metrik.api.TimePoint

/** Точка ряда для графика: `value == null` — данных нет, линия обязана разорваться. */
class ChartPoint(
    val at: Long,
    val value: Double?,
)

/**
 * Линия с заливкой-градиентом, разрывами на неполных окнах и отметками деплоев.
 *
 * Разрыв — не косметика: неполное окно или пропуск нельзя рисовать значением, иначе график
 * покажет спад нагрузки там, где на самом деле потерялись данные. Поэтому заливка и линия рисуются
 * отдельными непрерывными «пробегами» точек, а между пробегами тянется пунктир, а не сплошная
 * линия — как в макете (`gapPath`).
 */
@Composable
fun LineChart(
    title: String,
    points: List<ChartPoint>,
    deploys: List<DeployMarker> = emptyList(),
    color: Color = MaterialTheme.colorScheme.primary,
    showGrid: Boolean = false,
    // Заголовок и строка «последнее/max» — часть обычного графика в разделе «Система», но в
    // hero-графике и мини-графиках карточек их рисует вызывающий код сам (там другая раскладка
    // чисел), поэтому LineChart умеет быть «голым» — только сам холст.
    showHeader: Boolean = true,
    height: Dp = 140.dp,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(vertical = if (showHeader) Spacing.xs else 0.dp)) {
        if (showHeader) {
            Text(title, style = MaterialTheme.typography.labelLarge)
        }

        if (points.isEmpty()) {
            Text(
                "no data",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        val values = points.mapNotNull { it.value }
        val maxValue = (values.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
        // Именно последняя точка ряда, а не последнее известное значение: если сейчас разрыв,
        // это надо показать как «нет данных», а не подсовывать устаревшее число под видом свежего.
        val lastValue = points.last().value
        val minAt = points.minOf { it.at }
        val maxAt = points.maxOf { it.at }
        val span = (maxAt - minAt).coerceAtLeast(1)
        val markerColor = MaterialTheme.colorScheme.tertiary
        val gridColor = if (showGrid) MetrikExtra.chartGridLine else MaterialTheme.colorScheme.outlineVariant
        val gapColor = MetrikExtra.chartGapLine
        val surfaceColor = MaterialTheme.colorScheme.surfaceContainerLow
        val fillBrush = Brush.verticalGradient(listOf(color.copy(alpha = 0.40f), color.copy(alpha = 0f)))

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .padding(top = Spacing.xs)
                .background(surfaceColor, RoundedCornerShape(16.dp)),
        ) {
            fun x(at: Long) = ((at - minAt).toFloat() / span) * size.width

            fun y(value: Double) = size.height - (value / maxValue).toFloat() * size.height

            if (showGrid) {
                listOf(0.25f, 0.5f, 0.75f).forEach { fraction ->
                    val gridY = size.height * fraction
                    drawLine(gridColor, Offset(0f, gridY), Offset(size.width, gridY), strokeWidth = 1f)
                }
            }

            deploys.forEach { marker ->
                val markerX = x(marker.at)
                drawLine(
                    color = markerColor,
                    start = Offset(markerX, 0f),
                    end = Offset(markerX, size.height),
                    strokeWidth = 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 7f)),
                )
            }

            drawSeries(points, ::x, ::y, color, fillBrush, gapColor)
            drawRect(gridColor, style = Stroke(width = 1f))
        }

        if (showHeader) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "last " + (lastValue?.let { format(it) } ?: "no data"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "max ${format(maxValue)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Спарклайн для карточки сервиса — та же заливка-градиент под линией, что и в [LineChart], но
 * без заголовка, осей и рамки: в макете это просто `<svg>` внутри скруглённого контейнера карточки.
 */
@Composable
fun Sparkline(
    points: List<ChartPoint>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    if (points.isEmpty()) return

    val maxValue = (points.mapNotNull { it.value }.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
    val minAt = points.minOf { it.at }
    val maxAt = points.maxOf { it.at }
    val span = (maxAt - minAt).coerceAtLeast(1)
    val fillBrush = Brush.verticalGradient(listOf(color.copy(alpha = 0.38f), color.copy(alpha = 0f)))
    val gapColor = color.copy(alpha = 0.35f)

    Canvas(modifier.fillMaxWidth()) {
        fun x(at: Long) = ((at - minAt).toFloat() / span) * size.width

        fun y(value: Double) = size.height - (value / maxValue).toFloat() * size.height

        drawSeries(points, ::x, ::y, color, fillBrush, gapColor)
    }
}

/**
 * Общая отрисовка ряда для [LineChart] и [Sparkline]: разбивает точки на непрерывные «пробеги»
 * (границы — `null`-точки), заливает и обводит каждый пробег отдельно, а между пробегами тянет
 * пунктир — тот самый видимый разрыв на месте неполного окна.
 */
private fun DrawScope.drawSeries(
    points: List<ChartPoint>,
    x: (Long) -> Float,
    y: (Double) -> Float,
    color: Color,
    fillBrush: Brush,
    gapColor: Color,
) {
    val runs = mutableListOf<List<ChartPoint>>()
    var current = mutableListOf<ChartPoint>()
    points.forEach { point ->
        if (point.value == null) {
            if (current.isNotEmpty()) runs.add(current)
            current = mutableListOf()
        } else {
            current.add(point)
        }
    }
    if (current.isNotEmpty()) runs.add(current)

    // Пунктир между концом одного пробега и началом следующего — честный «разрыв», а не сглаженный
    // переход: значений между ними нет.
    for (i in 0 until runs.size - 1) {
        val a = runs[i].last()
        val b = runs[i + 1].first()
        drawLine(
            color = gapColor,
            start = Offset(x(a.at), y(a.value!!)),
            end = Offset(x(b.at), y(b.value!!)),
            strokeWidth = 2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 5f)),
        )
    }

    runs.forEach { run ->
        if (run.size < 2) {
            // Одна точка без соседей — рисовать нечего, кроме самой точки (см. ниже).
            return@forEach
        }
        val linePath = Path()
        run.forEachIndexed { index, point ->
            val px = x(point.at)
            val py = y(point.value!!)
            if (index == 0) linePath.moveTo(px, py) else linePath.lineTo(px, py)
        }

        val areaPath = Path()
        areaPath.addPath(linePath)
        areaPath.lineTo(x(run.last().at), size.height)
        areaPath.lineTo(x(run.first().at), size.height)
        areaPath.close()

        drawPath(areaPath, brush = fillBrush)
        drawPath(linePath, color = color, style = Stroke(width = 5f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
    }

    points.forEach { point ->
        val value = point.value ?: return@forEach
        drawCircle(color, radius = 2.5f, center = Offset(x(point.at), y(value)))
    }
}

/** Ряд с разрывами: точка неполного окна не рисуется значением. */
fun List<TimePoint>.toChart(selector: (TimePoint) -> Double): List<ChartPoint> =
    map { point -> ChartPoint(point.at, if (point.partial) null else selector(point)) }

fun format(value: Double): String =
    when {
        value >= 100 -> value.toLong().toString()
        value >= 10 -> ((value * 10).toLong() / 10.0).toString()
        else -> ((value * 100).toLong() / 100.0).toString()
    }
