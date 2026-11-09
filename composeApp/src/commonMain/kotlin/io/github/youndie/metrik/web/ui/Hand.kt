package io.github.youndie.metrik.web.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon

/**
 * The hand over something pressable, over its words too. The page's words are selectable ([SelectionArea]), and a
 * selectable text asks for the text cursor over itself; a hover icon gives way to its descendants' unless it overrides
 * them, so a row, a tab or a button drawn as words showed the text cursor over most of itself. A browser draws the hand
 * over a link's or a button's words. Put it right before the `clickable` it belongs to, so it covers the same bounds;
 * a disabled control gets no hand.
 */
fun Modifier.handOverWords(enabled: Boolean = true): Modifier =
    if (enabled) pointerHoverIcon(PointerIcon.Hand, overrideDescendants = true) else this
