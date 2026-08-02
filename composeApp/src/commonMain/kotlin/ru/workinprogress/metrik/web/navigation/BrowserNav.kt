package ru.workinprogress.metrik.web.navigation

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey

/**
 * Связывает кнопки «назад/вперёд» браузера и адресную строку с back stack'ом.
 *
 * В браузере навигация обязана быть настоящей: ими пользуются, даже если это не было задумано.
 * На таргетах без браузера (desktop) — no-op.
 */
@Composable
expect fun BrowserBackStackSync(backStack: NavBackStack<NavKey>)
