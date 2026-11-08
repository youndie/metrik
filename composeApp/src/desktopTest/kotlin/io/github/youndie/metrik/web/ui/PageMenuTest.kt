package io.github.youndie.metrik.web.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import io.github.youndie.metrik.web.navigation.Route
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * M-121, M-122: слова страницы выделяются и копируются, правая кнопка открывает меню дашборда у указателя —
 * над словами «Copy» и «Select all», над ссылкой «Open in new tab» и «Copy link». Меню Compose на wasm
 * рисовалось в чужом оформлении и не делало ничего, а ⌘C не копировал: копия Compose ждёт браузерного `copy`.
 */
@OptIn(ExperimentalTestApi::class)
class PageMenuTest {
    private val copied = mutableListOf<String>()
    private val opened = mutableListOf<String>()

    private val browser =
        object : BrowserActions {
            override fun copyText(text: String) {
                copied += text
            }

            override fun address(fragment: String): String = ORIGIN + fragment

            override val opensTabs: Boolean = true

            override fun openInNewTab(fragment: String) {
                opened += fragment
            }

            override fun setTitle(title: String) = Unit
        }

    private fun ComposeUiTest.page() =
        setContent {
            MetrikTheme {
                CompositionLocalProvider(LocalBrowserActions provides browser) {
                    Selectable {
                        Column {
                            SelectionArea {
                                BasicText(RAIL_WORDS, Modifier.testTag(RAIL))
                            }
                            SelectionArea {
                                Column {
                                    BasicText(WORDS, Modifier.testTag(TEXT))
                                    Box(Modifier.size(80.dp, 40.dp).testTag(LINK).offersLink(Route.Service(3)))
                                }
                            }
                        }
                    }
                }
            }
        }

    private fun ComposeUiTest.rightClick(tag: String) {
        onNodeWithTag(tag).performMouseInput { rightClick() }
        waitForIdle()
    }

    private fun ComposeUiTest.pick(entry: String) {
        onNodeWithText(entry).performClick()
        waitForIdle()
    }

    @Test
    fun `the right button over the words opens the dashboard's menu with Select all and no Copy`() =
        runComposeUiTest {
            page()
            rightClick(TEXT)
            onNodeWithTag(PAGE_MENU_TAG).assertExists()
            onNodeWithText(SELECT_ALL).assertExists()
            // Ничего ещё не выделено — копировать нечего.
            onNodeWithText(COPY).assertDoesNotExist()
        }

    @Test
    fun `Copy writes the selected words through the browser's clipboard`() =
        runComposeUiTest {
            page()
            rightClick(TEXT)
            pick(SELECT_ALL)
            onNodeWithTag(PAGE_MENU_TAG).assertDoesNotExist()
            rightClick(TEXT)
            pick(COPY)
            assertEquals(listOf(WORDS), copied)
        }

    @Test
    fun `the copy shortcut copies the words selected`() =
        runComposeUiTest {
            page()
            rightClick(TEXT)
            pick(SELECT_ALL)
            onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.C) } }
            waitForIdle()
            assertEquals(listOf(WORDS), copied)
        }

    @Test
    fun `Select all takes the area under the pointer, not the rail beside it`() =
        runComposeUiTest {
            page()
            rightClick(RAIL)
            pick(SELECT_ALL)
            onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.C) } }
            waitForIdle()
            // Одно выделение на страницу: выделив экран, рельс отпускает своё — иначе копия брала бы
            // рельс, который стоит в странице раньше.
            rightClick(TEXT)
            pick(SELECT_ALL)
            onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.C) } }
            waitForIdle()
            assertEquals(listOf(RAIL_WORDS, WORDS), copied)
        }

    @Test
    fun `the right button over a link offers to open it apart and to copy its address`() =
        runComposeUiTest {
            page()
            rightClick(LINK)
            onNodeWithText(SELECT_ALL).assertDoesNotExist()
            pick(OPEN_IN_NEW_TAB)
            assertEquals(listOf("#service?id=3"), opened)
            rightClick(LINK)
            pick(COPY_LINK)
            assertEquals(listOf("$ORIGIN#service?id=3"), copied)
        }

    @Test
    fun `Escape closes the menu`() =
        runComposeUiTest {
            page()
            rightClick(TEXT)
            onNodeWithTag(PAGE_MENU_TAG).assertExists()
            onRoot().performKeyInput { pressKey(Key.Escape) }
            waitForIdle()
            onNodeWithTag(PAGE_MENU_TAG).assertDoesNotExist()
        }

    @Test
    fun `Escape closes the menu while the words stay selected`() =
        runComposeUiTest {
            page()
            rightClick(TEXT)
            pick(SELECT_ALL)
            rightClick(TEXT)
            onNodeWithText(COPY).assertExists()
            onRoot().performKeyInput { pressKey(Key.Escape) }
            waitForIdle()
            onNodeWithTag(PAGE_MENU_TAG).assertDoesNotExist()
            // Меню не забрало фокус у выделения, и оно осталось.
            onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.C) } }
            waitForIdle()
            assertEquals(listOf(WORDS), copied)
        }

    @Test
    fun `the right button off an open menu opens the menu where it pressed`() =
        runComposeUiTest {
            page()
            rightClick(TEXT)
            onNodeWithText(SELECT_ALL).assertExists()
            rightClick(LINK)
            onNodeWithText(COPY_LINK).assertExists()
            onNodeWithText(SELECT_ALL).assertDoesNotExist()
        }

    private companion object {
        const val TEXT = "text"
        const val RAIL = "rail"
        const val LINK = "link"
        const val WORDS = "GET /users/{id} 404"
        const val RAIL_WORDS = "sample-jvm"
        const val ORIGIN = "https://metrik.example/"
    }
}
