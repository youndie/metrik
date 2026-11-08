package io.github.youndie.metrik.web.ui

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.youndie.metrik.api.DeployMarker
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * M-124: у графика не было способа узнать значение точки, кроме «последнее» и «max» в шапке. Под
 * указателем — время окна и значение; неполное окно называется неполным, а не нулём.
 */
@OptIn(ExperimentalTestApi::class)
class ChartHoverTest {
    private fun ComposeUiTest.chart() =
        setContent {
            MetrikTheme {
                CompositionLocalProvider(LocalChartWindow provides TimeWindow(FROM, FROM + 4 * MINUTE)) {
                    LineChart(
                        title = "",
                        points = POINTS,
                        showHeader = false,
                        unit = "rps",
                        modifier = Modifier.width(WIDTH.dp).testTag(CHART),
                    )
                }
            }
        }

    /** Указатель над точкой с индексом [minute] — по оси от `from` до `from + 4 мин`. */
    private fun ComposeUiTest.hover(minute: Int) {
        onNodeWithTag(CHART).performMouseInput {
            moveTo(Offset(width * minute / 4f, height / 2f))
        }
        waitForIdle()
    }

    @Test
    fun `the pointer over the chart names the point's value`() =
        runComposeUiTest {
            chart()
            onNodeWithTag(CHART_TOOLTIP_TAG).assertDoesNotExist()
            hover(1)
            onNodeWithTag(CHART_TOOLTIP_TAG).assertExists()
            // Время зависит от зоны машины, значение — нет.
            val text =
                onNodeWithTag(CHART_TOOLTIP_TAG)
                    .fetchSemanticsNode()
                    .config[androidx.compose.ui.semantics.SemanticsProperties.Text]
                    .joinToString()
            assert(text.endsWith("· 12.5 rps")) { text }
        }

    @Test
    fun `a partial window is named partial, not zero`() =
        runComposeUiTest {
            chart()
            hover(2)
            val text =
                onNodeWithTag(CHART_TOOLTIP_TAG)
                    .fetchSemanticsNode()
                    .config[androidx.compose.ui.semantics.SemanticsProperties.Text]
                    .joinToString()
            assert(text.endsWith("· partial window — no value")) { text }
        }

    @Test
    fun `the label goes when the pointer leaves the chart`() =
        runComposeUiTest {
            chart()
            hover(1)
            onNodeWithTag(CHART_TOOLTIP_TAG).assertExists()
            onNodeWithTag(CHART).performMouseInput { moveTo(Offset(-50f, -50f)) }
            waitForIdle()
            onNodeWithTag(CHART_TOOLTIP_TAG).assertDoesNotExist()
        }

    /**
     * M-123: ряд короче периода стоит на своём времени. Ось по размаху точек растягивала две последние
     * минуты на всю ширину, и под тремя четвертями ширины оказывалась последняя точка, а не минута 3.
     */
    @Test
    fun `a series shorter than the period sits at its own time`() =
        runComposeUiTest {
            setContent {
                MetrikTheme {
                    CompositionLocalProvider(LocalChartWindow provides TimeWindow(FROM, FROM + 4 * MINUTE)) {
                        LineChart(
                            title = "",
                            points = listOf(ChartPoint(FROM + 3 * MINUTE, 1.0), ChartPoint(FROM + 4 * MINUTE, 2.0)),
                            showHeader = false,
                            unit = "rps",
                            modifier = Modifier.width(WIDTH.dp).testTag(CHART),
                        )
                    }
                }
            }
            hover(3)
            val text =
                onNodeWithTag(CHART_TOOLTIP_TAG)
                    .fetchSemanticsNode()
                    .config[androidx.compose.ui.semantics.SemanticsProperties.Text]
                    .joinToString()
            assert(text.endsWith("· 1.0 rps")) { text }
        }

    @Test
    fun `the label reads the local time, the value with its unit and a deploy beside it`() {
        val label = hoverLabel(ChartPoint(AT, 3.0), "ms", DeployMarker("0.3.1", AT), TimeZone.UTC)
        assertEquals("Nov 8, 14:05 · 3.0 ms · deploy 0.3.1", label)
        assertEquals("Nov 8, 14:05 · 0.5", hoverLabel(ChartPoint(AT, 0.5), "", null, TimeZone.UTC))
    }

    @Test
    fun `the nearest point by time is the one under the pointer`() {
        val nearest = nearestPoint(POINTS, pointerX = 260f, widthPx = 400f, minAt = FROM, span = 4 * MINUTE)
        // 260 из 400 пикселей — 2,6 минуты: ближе всего минута 3.
        assertEquals(FROM + 3 * MINUTE, nearest?.at)
    }

    private companion object {
        const val CHART = "chart"
        const val WIDTH = 400
        const val MINUTE = 60_000L
        const val FROM = 1_794_150_000_000L

        // 2026-11-08 14:05 UTC
        const val AT = 1_794_146_700_000L

        val POINTS =
            listOf(
                ChartPoint(FROM, 10.0),
                ChartPoint(FROM + MINUTE, 12.5),
                ChartPoint(FROM + 2 * MINUTE, null),
                ChartPoint(FROM + 3 * MINUTE, 11.0),
                ChartPoint(FROM + 4 * MINUTE, 9.0),
            )
    }
}
