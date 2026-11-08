package io.github.youndie.metrik.web.feature.service.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.youndie.metrik.api.ServiceSummary
import io.github.youndie.metrik.api.SlowRow
import io.github.youndie.metrik.web.ui.MetrikTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M-129: «Медленные» были карточкой в два этажа на запрос — семь запросов на 900 пикселей, и длительности
 * не сравнивались по столбцу. На десктопе это таблица, строка на запрос; на телефоне — карточки, как у
 * маршрутов (M-89).
 */
@OptIn(ExperimentalTestApi::class)
class SlowTabTest {
    private fun ComposeUiTest.screen(compact: Boolean) =
        setContent {
            MetrikTheme {
                ServiceContent(uiState = STATE, onAction = {}, compact = compact)
            }
        }

    @Test
    fun `on a desktop each slow request is one table row`() =
        runComposeUiTest {
            screen(compact = false)
            val rows = onAllNodesWithTag(SLOW_ROW_TAG).fetchSemanticsNodes()
            assertEquals(ROWS, rows.size)
            rows.forEach { node ->
                val height = with(density) { node.size.height.toDp() }
                assertTrue(height <= 48.dp, "a row is $height high")
            }
            // Длительность — числом в колонке «DURATION, MS», а не «3200 ms» в каждой строке.
            assertEquals(1, onAllNodesWithText("DURATION, MS").fetchSemanticsNodes().size)
        }

    @Test
    fun `on a phone the requests stay cards`() =
        runComposeUiTest {
            screen(compact = true)
            assertEquals(0, onAllNodesWithTag(SLOW_ROW_TAG).fetchSemanticsNodes().size)
            assertEquals(ROWS, onAllNodesWithText("/slow", substring = true).fetchSemanticsNodes().size)
        }

    private companion object {
        const val ROWS = 10
        val STATE =
            ServiceUiState(
                serviceId = 3,
                service =
                    ServiceSummary(
                        3,
                        "sample-jvm",
                        1.0,
                        0.0,
                        1.0,
                        lastSeenAt = 1L,
                        instances = 1,
                        clockSkew = false,
                    ),
                tab = ServiceTab.SLOW,
                slow = (1..ROWS).map { SlowRow("GET", "/slow/$it", 2, 400 * it, 1_794_150_000_000L) },
                loaded = true,
                nowMs = 1_794_150_060_000L,
            )
    }
}
