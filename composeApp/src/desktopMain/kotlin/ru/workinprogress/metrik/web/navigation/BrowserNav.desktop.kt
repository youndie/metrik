package ru.workinprogress.metrik.web.navigation

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey

@Composable
actual fun BrowserBackStackSync(backStack: NavBackStack<NavKey>) = Unit
