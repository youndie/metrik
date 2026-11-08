package io.github.youndie.metrik.web.feature.alerts.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import io.github.youndie.metrik.api.AlertDelivery
import io.github.youndie.metrik.web.ui.MetrikTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * M-132: шапка экрана алертов писала «TELEGRAM · 30 MIN COOLDOWN» всегда — и при ненастроенном Telegram
 * обещала доставку, которой нет. Подпись — то, что настроил сервер.
 */
@OptIn(ExperimentalTestApi::class)
class AlertDeliveryLabelTest {
    @Test
    fun `the label names the channel and the cooldown the server reports`() {
        assertEquals("TELEGRAM · 30 MIN COOLDOWN", deliveryLabel(AlertDelivery("telegram", 30)))
        assertEquals("TELEGRAM · 15 MIN COOLDOWN", deliveryLabel(AlertDelivery("telegram", 15)))
    }

    @Test
    fun `with no channel the label says alerts show here only`() {
        assertEquals("NO DELIVERY · ALERTS SHOW HERE ONLY", deliveryLabel(AlertDelivery(null, 30)))
    }

    @Test
    fun `before the server answers there is no label rather than a made-up one`() {
        assertNull(deliveryLabel(null))
    }

    @Test
    fun `the screen shows the server's answer on a desktop and on a phone`() {
        for (compact in listOf(false, true)) {
            runComposeUiTest {
                setContent {
                    MetrikTheme {
                        AlertsContent(
                            uiState = AlertsUiState(delivery = AlertDelivery(null, 30)),
                            onAction = {},
                            compact = compact,
                        )
                    }
                }
                onNodeWithText("NO DELIVERY · ALERTS SHOW HERE ONLY").assertExists()
                onNodeWithText("TELEGRAM", substring = true).assertDoesNotExist()
            }
        }
    }
}
