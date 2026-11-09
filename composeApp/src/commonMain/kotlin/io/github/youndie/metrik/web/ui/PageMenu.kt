package io.github.youndie.metrik.web.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import io.github.youndie.metrik.web.navigation.Route
import io.github.youndie.metrik.web.navigation.fragment
import kotlin.math.roundToInt

// Холст Compose забирает правую кнопку себе, и меню браузера над дашбордом не открывается никогда. Своё
// текстовое меню Compose на wasm — старое (новое, настраиваемое, на skiko выключено в 1.12): «Copy» и
// «Select all» в чужом оформлении, и ни один пункт ничего не делал (найдено в haul B-154, тот же Compose).
// Поэтому правую кнопку читает страница сама и открывает меню дашборда у указателя (M-122), а меню Compose
// не открывается никогда.

/** Пункт меню: подпись и то, что он делает. */
class MenuEntry(
    val label: String,
    val onPress: () -> Unit,
)

/** Открытое меню: где (в координатах [Selectable]) и что предлагает. */
internal class PointMenuShown(
    val at: Offset,
    val entries: List<MenuEntry>,
)

/**
 * Меню правой кнопки на странице [Selectable] — открытое, если есть, — области страницы, у которых меню
 * своё ([offersMenu], [offersLink]), и области выделения ([SelectionArea]). Правую кнопку читает одна
 * страница и ищет область под указателем здесь: отдельный читатель на каждой ссылке пропускал нажатия,
 * которые видел читатель страницы (haul B-154).
 */
@Stable
internal class PageMenus {
    var shown: PointMenuShown? by mutableStateOf(null)

    private val areas = mutableListOf<MenuArea>()

    val selections = mutableListOf<SelectionAreaState>()

    fun add(area: MenuArea) {
        areas += area
    }

    fun remove(area: MenuArea) {
        areas -= area
    }

    /** Самая вложенная (наименьшая) область со своим меню, нарисованная в точке [position] окна. */
    fun areaAt(position: Offset): MenuArea? = areas.smallestAt(position) { it.coordinates }

    /** Область выделения в точке [position] окна. */
    fun selectionAt(position: Offset): SelectionAreaState? = selections.smallestAt(position) { it.coordinates }

    /** Выделенные слова — той области, где они есть; пусто, если не выделено ничего. */
    fun selectedWords(): String =
        selections.firstNotNullOfOrNull { area -> area.words().takeIf { it.isNotEmpty() } }.orEmpty()
}

private fun <T> List<T>.smallestAt(
    position: Offset,
    coordinates: (T) -> LayoutCoordinates?,
): T? =
    mapNotNull { item ->
        coordinates(item)
            ?.takeIf { it.isAttached }
            ?.boundsInRoot()
            ?.let { item to it }
    }.filter { (_, bounds) -> bounds.contains(position) }
        .minByOrNull { (_, bounds) -> bounds.width * bounds.height }
        ?.first

/** Область со своим меню: где нарисована и что предлагает в момент нажатия. */
internal class MenuArea(
    var entries: () -> List<MenuEntry>,
) {
    var coordinates: LayoutCoordinates? = null
}

/** Область выделения страницы: её состояние и где она нарисована. */
internal class SelectionAreaState(
    val selection: SelectionState,
) {
    var coordinates: LayoutCoordinates? = null

    fun words(): String = selection.selectedTexts.joinToString("\n") { it.text }
}

internal val LocalPageMenus: ProvidableCompositionLocal<PageMenus?> = staticCompositionLocalOf { null }

/**
 * Страница, на которой правая кнопка открывает меню дашборда (M-122): над словами — «Copy» (если что-то
 * выделено) и «Select all», над ссылкой — «Open in new tab» и «Copy link». ⌘C / Ctrl+C копирует выделенное
 * через [LocalBrowserActions]: копия Compose на wasm ждёт браузерного события `copy`, которое не приходит.
 *
 * Слова выделяются в [SelectionArea] внутри неё (M-121). Меню рисуется поверх [content] в этом же боксе,
 * поэтому [Selectable] оборачивает всё окно, а не экран.
 */
@Composable
fun Selectable(content: @Composable () -> Unit) {
    val menus = remember { PageMenus() }
    val browser = LocalBrowserActions.current
    val placed = remember { Placed() }
    // Меню в точке страницы: своё у области под указателем, иначе — меню слов.
    val open: (Offset) -> Unit = { local ->
        val inRoot = placed.coordinates?.localToRoot(local) ?: local
        val entries =
            menus
                .areaAt(inRoot)
                ?.entries
                ?.invoke()
                ?.takeIf { it.isNotEmpty() }
                ?: textEntries(menus, menus.selectionAt(inRoot), browser)
        // Предложить нечего — нечего и открывать: пустая карточка у указателя выглядит поломкой.
        menus.shown = entries.takeIf { it.isNotEmpty() }?.let { PointMenuShown(local, it) }
    }
    CompositionLocalProvider(LocalPageMenus provides menus) {
        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { placed.coordinates = it }
                .onPreviewKeyEvent { key ->
                    // Escape закрывает меню и тогда, когда фокус остался у выделения (см. [MenuCard]).
                    if (key.key == Key.Escape && menus.shown != null) {
                        if (key.type == KeyEventType.KeyDown) menus.shown = null
                        return@onPreviewKeyEvent true
                    }
                    val copies =
                        key.type == KeyEventType.KeyDown &&
                            key.key == Key.C &&
                            (key.isMetaPressed || key.isCtrlPressed)
                    val words = menus.selectedWords()
                    if (copies && words.isNotEmpty() && browser != null) {
                        browser.copyText(words)
                        true
                    } else {
                        false
                    }
                }.onRightPress(open),
            propagateMinConstraints = true,
        ) {
            content()
            menus.shown?.let { shown ->
                PointMenu(
                    shown,
                    takesFocus = menus.selectedWords().isEmpty(),
                    onClose = { menus.shown = null },
                    onRightPressOff = open,
                )
            }
        }
    }
}

/**
 * [content], слова которого выделяются — протяжкой, двойным нажатием (M-121). Областей на странице несколько:
 * рельс и экран — разные колонки, а выделение Compose упорядочивает тексты по положению, а не по колонкам, и
 * протяжка по таблице маршрутов захватывала строку рельса посередине копии.
 *
 * Выделение на странице всё равно одно, как у браузера: выделяя в одной области, она берёт фокус, а область,
 * потерявшая фокус, своё выделение снимает сама (`SelectionContainer`). Своё снятие здесь было и убрано —
 * с ним и без него `PageMenuTest` одинаково зелёный.
 */
@Composable
fun SelectionArea(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val menus = LocalPageMenus.current
    val selection = rememberSelectionState()
    val area = remember(selection) { SelectionAreaState(selection) }
    if (menus != null) {
        DisposableEffect(menus, area) {
            menus.selections += area
            onDispose { menus.selections -= area }
        }
    }
    Box(modifier.onGloballyPositioned { area.coordinates = it }, propagateMinConstraints = true) {
        SelectionContainer(selection) { content() }
    }
}

/**
 * Что правая кнопка предлагает над словами: «Copy», если что-то выделено и есть чем копировать, и «Select all»
 * области под указателем ([under]); вне областей выделения — только «Copy».
 */
private fun textEntries(
    menus: PageMenus,
    under: SelectionAreaState?,
    browser: BrowserActions?,
): List<MenuEntry> {
    val words = menus.selectedWords()
    return listOfNotNull(
        if (words.isNotEmpty() && browser != null) MenuEntry(COPY) { browser.copyText(words) } else null,
        under?.let { MenuEntry(SELECT_ALL) { it.selection.selectAll() } },
    )
}

/**
 * Правая кнопка над этим открывает меню ссылки на [route]: «Open in new tab» (где есть вкладки) и «Copy link».
 * Вне [Selectable] или без [LocalBrowserActions] — ничего.
 */
@Composable
fun Modifier.offersLink(route: Route): Modifier {
    val browser = LocalBrowserActions.current ?: return this
    return offersMenu {
        val fragment = route.fragment()
        listOfNotNull(
            if (browser.opensTabs) MenuEntry(OPEN_IN_NEW_TAB) { browser.openInNewTab(fragment) } else null,
            MenuEntry(COPY_LINK) { browser.copyText(browser.address(fragment)) },
        )
    }
}

/**
 * Правая кнопка над этим открывает [entries] — спрошенные в момент нажатия — вместо меню слов. Пустой список
 * оставляет меню слов. Вне [Selectable] — ничего.
 */
@Composable
fun Modifier.offersMenu(entries: () -> List<MenuEntry>): Modifier {
    val menus = LocalPageMenus.current ?: return this
    val area = remember(menus) { MenuArea(entries) }
    area.entries = entries
    DisposableEffect(menus, area) {
        menus.add(area)
        onDispose { menus.remove(area) }
    }
    return onGloballyPositioned { area.coordinates = it }
}

/**
 * [onPress] на нажатии правой кнопки над этим — прочитанном раньше всех под ним и забранном: меню Compose ждёт
 * незабранного нажатия и поэтому не открывается. Каждое событие читается само по себе, а не жестом до
 * отпускания: открытое нажатием меню накрывает страницу раньше, чем придёт отпускание, и жест ждал бы его вечно.
 * Читатель не перезапускается — нажатие во время перезапуска потерялось бы, — поэтому [onPress] читается текущий.
 */
@Composable
private fun Modifier.onRightPress(onPress: (Offset) -> Unit): Modifier {
    val current by rememberUpdatedState(onPress)
    return pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                    event.changes.forEach { it.consume() }
                    current(event.changes.first().position)
                }
            }
        }
    }
}

/** Где бокс страницы стоит в окне — читается в момент нажатия. */
private class Placed {
    var coordinates: LayoutCoordinates? = null
}

const val COPY: String = "Copy"
const val SELECT_ALL: String = "Select all"
const val OPEN_IN_NEW_TAB: String = "Open in new tab"
const val COPY_LINK: String = "Copy link"
const val PAGE_MENU_TAG: String = "page-menu"

/**
 * Пункты [shown] в его точке поверх страницы: ниже и правее указателя, а где не помещается — левее или выше,
 * как у браузера. Нажатие мимо меню закрывает его ([onClose]); правой кнопкой — открывает меню там, где нажали
 * ([onRightPressOff]). Escape закрывает. Пункт закрывает меню и делает своё.
 */
@Composable
private fun PointMenu(
    shown: PointMenuShown,
    takesFocus: Boolean,
    onClose: () -> Unit,
    onRightPressOff: (Offset) -> Unit,
) {
    val drawn = remember { arrayOf(IntRect.Zero) }
    Layout(
        content = { MenuCard(shown.entries, takesFocus, onClose) },
        modifier =
            Modifier.fillMaxSize().pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        // Меню браузера закрывается на нажатии, а не на отпускании. `awaitFirstDown` читает
                        // только левую кнопку мыши, поэтому события читаются как есть.
                        val event = awaitPointerEvent()
                        val press = event.changes.first()
                        if (event.type == PointerEventType.Press && !drawn[0].contains(press.position.round())) {
                            press.consume()
                            if (event.buttons.isSecondaryPressed) onRightPressOff(press.position) else onClose()
                        }
                    }
                }
            },
    ) { measurables, constraints ->
        val menu = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val window = IntSize(constraints.maxWidth, constraints.maxHeight)
        val x =
            shown.at.x
                .roundToInt()
                .let { if (it + menu.width > window.width) it - menu.width else it }
        val y =
            shown.at.y
                .roundToInt()
                .let { if (it + menu.height > window.height) it - menu.height else it }
        val at =
            IntOffset(
                x.coerceIn(0, (window.width - menu.width).coerceAtLeast(0)),
                y.coerceIn(0, (window.height - menu.height).coerceAtLeast(0)),
            )
        drawn[0] = IntRect(at, IntSize(menu.width, menu.height))
        layout(window.width, window.height) { menu.place(at) }
    }
}

@Composable
private fun MenuCard(
    entries: List<MenuEntry>,
    takesFocus: Boolean,
    onClose: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val focus = remember { FocusRequester() }
    // Фокус меню берёт, только если на странице ничего не выделено: ушедший фокус снимает выделение Compose,
    // и подсветка слов гасла, пока меню над ними открыто. С выделением фокус остаётся у него, а Escape ловит
    // страница ([Selectable]).
    LaunchedEffect(focus, takesFocus) { if (takesFocus) focus.requestFocus() }
    Column(
        Modifier
            .testTag(PAGE_MENU_TAG)
            .focusRequester(focus)
            .onPreviewKeyEvent {
                val escape = it.key == Key.Escape
                if (escape && it.type == KeyEventType.KeyDown) onClose()
                escape
            }.focusable()
            .width(IntrinsicSize.Max)
            .widthIn(min = 180.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(vertical = Spacing.xs),
    ) {
        entries.forEach { entry ->
            Text(
                entry.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                softWrap = false,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .handOverWords()
                        .clickable {
                            onClose()
                            entry.onPress()
                        }.padding(horizontal = Spacing.lg, vertical = Spacing.sm + Spacing.xxs),
            )
        }
    }
}
