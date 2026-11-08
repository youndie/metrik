package io.github.youndie.metrik.web.feature.service.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import io.github.youndie.metrik.api.RouteRow
import io.github.youndie.metrik.api.ServiceSummary
import io.github.youndie.metrik.web.ui.MetrikTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M-128: единственное разрушительное действие дашборда стояло серой надписью прямо под «1h / 24h / 7d»,
 * в одном движении мыши от самого частого нажатия, а на телефоне его не было вовсе. Теперь — внизу
 * страницы, под содержимым вкладки, на обеих раскладках.
 */
@OptIn(ExperimentalTestApi::class)
class RemoveFromMonitoringTest {
    private val actions = mutableListOf<ServiceUiAction>()

    private fun ComposeUiTest.screen(
        compact: Boolean,
        state: ServiceUiState = STATE,
    ) = setContent {
        MetrikTheme {
            ServiceContent(uiState = state, onAction = { actions += it }, compact = compact)
        }
    }

    private fun ComposeUiTest.assertBelowTheTab() {
        val section = onNodeWithTag(REMOVE_SECTION_TAG).performScrollTo().getBoundsInRoot()
        val lastRoute = onNodeWithText("/last").getBoundsInRoot()
        assertTrue(
            section.top >= lastRoute.bottom,
            "the section at ${section.top}, the tab's last row ends at ${lastRoute.bottom}",
        )
    }

    @Test
    fun `on a desktop the removal sits below the tab, not under the range`() =
        runComposeUiTest {
            screen(compact = false)
            assertBelowTheTab()
        }

    @Test
    fun `on a phone the removal is there at all, below the tab`() =
        runComposeUiTest {
            screen(compact = true)
            assertBelowTheTab()
        }

    @Test
    fun `the first press only asks`() =
        runComposeUiTest {
            screen(compact = false)
            onNodeWithText("Remove from monitoring").performScrollTo().performClick()
            assertEquals(listOf<ServiceUiAction>(ServiceUiAction.RequestDelete), actions)
        }

    @Test
    fun `the confirmation deletes`() =
        runComposeUiTest {
            screen(compact = true, state = STATE.copy(deleteRequested = true))
            onNodeWithText("Delete").performScrollTo().performClick()
            assertEquals(listOf<ServiceUiAction>(ServiceUiAction.ConfirmDelete), actions)
        }

    private companion object {
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
                tab = ServiceTab.ROUTES,
                routes = (1..8).map { RouteRow("GET", if (it == 8) "/last" else "/r$it", 2, 10L * it, 1.0, 2.0, 3) },
                loaded = true,
            )
    }
}
