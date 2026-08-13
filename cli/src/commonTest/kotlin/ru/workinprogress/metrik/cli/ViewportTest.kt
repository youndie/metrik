package ru.workinprogress.metrik.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Кадр обязан помещаться в терминал.
 *
 * Это не косметика: кадр выше экрана ломает перерисовку Mosaic — курсор поднимается не на столько
 * строк, сколько реально видно, и обрывки прошлого кадра остаются поверх нового. Ровно так на
 * снимке 70×28 подсказка по клавишам оказалась напечатанной трижды.
 */
class ViewportTest {
    private fun rows(count: Int) = (1..count).map { row("line $it") }

    @Test
    fun `a frame taller than the terminal is cut to it`() {
        assertEquals(5, rows(40).clampHeight(5).size)
    }

    @Test
    fun `a short frame is left alone`() {
        assertEquals(3, rows(3).clampHeight(10).size)
    }

    @Test
    fun `the row that must stay visible stays visible`() {
        // Given a selection below the fold
        val visible = rows(40).clampHeight(5, keepVisible = 30).map { it.plain() }

        // Then the window moved to it — otherwise the cursor moves and the user cannot see where
        assertTrue(visible.contains("line 31"), "окно должно показывать выбранную строку: $visible")
    }

    @Test
    fun `the window never runs past the end`() {
        val visible = rows(10).clampHeight(4, keepVisible = 9).map { it.plain() }

        assertEquals(listOf("line 7", "line 8", "line 9", "line 10"), visible)
    }

    @Test
    fun `a row wider than the terminal is truncated with an ellipsis`() {
        val line = row("/api/services/{id}/timeseries").clampWidth(10).plain()

        // Многоточие, а не обрыв: укороченное имя маршрута иначе читается как другой маршрут
        assertEquals(10, line.length)
        assertTrue(line.endsWith("…"), line)
    }

    @Test
    fun `a row that fits is not touched`() {
        assertEquals("GET /health", row("GET /health").clampWidth(40).plain())
    }

    @Test
    fun `clamping applies to both axes at once`() {
        // Обе оси одним вызовом — чтобы обрезку нельзя было забыть наполовину
        val frame = listOf(row("а".repeat(100)), row("б".repeat(100)), row("в".repeat(100)))
        val clamped = frame.clamp(width = 10, height = 2)

        assertEquals(2, clamped.size)
        assertTrue(clamped.all { it.size == 10 })
    }
}
