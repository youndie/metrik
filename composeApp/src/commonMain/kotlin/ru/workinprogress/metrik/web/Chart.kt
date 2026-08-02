package ru.workinprogress.metrik.web

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import ru.workinprogress.metrik.api.DeployMarker
import ru.workinprogress.metrik.api.TimePoint

/** Точка ряда для графика: `value == null` — данных нет, линия обязана разорваться. */
class ChartPoint(
    val at: Long,
    val value: Double?,
)

/**
 * Линия с разрывами и отметками деплоев.
 *
 * Разрыв — не косметика: неполное окно или пропуск нельзя рисовать значением, иначе график
 * покажет спад нагрузки там, где на самом деле потерялись данные.
 */
@Composable
fun LineChart(
    title: String,
    points: List<ChartPoint>,
    deploys: List<DeployMarker> = emptyList(),
    color: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)

        if (points.isEmpty()) {
            Text("нет данных", style = MaterialTheme.typography.bodySmall)
            return@Column
        }

        val values = points.mapNotNull { it.value }
        val maxValue = (values.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
        val minAt = points.minOf { it.at }
        val maxAt = points.maxOf { it.at }
        val span = (maxAt - minAt).coerceAtLeast(1)
        val markerColor = MaterialTheme.colorScheme.tertiary
        val gridColor = MaterialTheme.colorScheme.outlineVariant

        Canvas(Modifier.fillMaxWidth().height(140.dp).padding(top = 4.dp)) {
            val width = size.width
            val height = size.height

            drawLine(gridColor, Offset(0f, height), Offset(width, height), strokeWidth = 1f)

            fun x(at: Long) = ((at - minAt).toFloat() / span) * width

            fun y(value: Double) = height - (value / maxValue).toFloat() * height

            deploys.forEach { marker ->
                val markerX = x(marker.at)
                drawLine(
                    color = markerColor,
                    start = Offset(markerX, 0f),
                    end = Offset(markerX, height),
                    strokeWidth = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                )
            }

            var previous: ChartPoint? = null
            points.forEach { point ->
                val value = point.value
                val last = previous

                if (value != null && last?.value != null) {
                    drawLine(
                        color = color,
                        start = Offset(x(last.at), y(last.value)),
                        end = Offset(x(point.at), y(value)),
                        strokeWidth = 2f,
                    )
                }

                if (value != null) {
                    drawCircle(color, radius = 2.5f, center = Offset(x(point.at), y(value)))
                }

                previous = point
            }

            drawRect(gridColor, style = Stroke(width = 1f))
        }

        Text(
            "max ${format(maxValue)}",
            style = MaterialTheme.typography.bodySmall,
        )
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
