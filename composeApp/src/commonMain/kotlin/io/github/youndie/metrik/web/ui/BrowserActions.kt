package io.github.youndie.metrik.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * То, что страница браузера умеет сама, а холст Compose — нет: положить текст в буфер обмена и открыть
 * адрес в новой вкладке (M-121, M-122).
 *
 * Буфер — не `LocalClipboard` Compose: на wasm его копирование ждёт браузерного события `copy`, которое
 * над холстом не приходит, и ⌘C молча не копировал ничего (Compose 1.12; то же найдено в haul B-154).
 */
interface BrowserActions {
    /** Кладёт [text] в буфер обмена как есть. */
    fun copyText(text: String)

    /** Полный адрес для [fragment] (`#service?id=3`) — origin страницы и фрагмент. */
    fun address(fragment: String): String

    /** Умеет ли платформа открыть вкладку: на desktop-отладке вкладок нет, и пункт меню не предлагается. */
    val opensTabs: Boolean

    /** Открывает [fragment] в новой вкладке, как «Open in new tab» браузера. */
    fun openInNewTab(fragment: String)

    /** Заголовок вкладки браузера (M-131); на desktop — ничего. */
    fun setTitle(title: String)
}

/** Платформенная реализация: на wasm — `navigator.clipboard` и `window.open`, на desktop — буфер AWT. */
expect fun platformBrowserActions(): BrowserActions

/**
 * Вкладка браузера называет экран — «sample-jvm · metrik», а не «metrik» у всех пяти открытых сервисов
 * (M-131). [screen] `null` — экран своего имени ещё не знает, и вкладка зовётся просто «metrik».
 */
@Composable
fun PageTitle(screen: String?) {
    val browser = LocalBrowserActions.current ?: return
    LaunchedEffect(screen) { browser.setTitle(screen?.let { "$it · metrik" } ?: "metrik") }
}

/** `null` — нечем копировать (снимок, тест без подмены): меню не предлагает ни копирования, ни ссылок. */
val LocalBrowserActions: ProvidableCompositionLocal<BrowserActions?> = staticCompositionLocalOf { null }
