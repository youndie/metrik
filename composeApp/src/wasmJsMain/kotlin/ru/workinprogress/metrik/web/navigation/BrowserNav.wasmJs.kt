package ru.workinprogress.metrik.web.navigation

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.github.terrakok.navigation3.browser.ChronologicalBrowserNavigation
import com.github.terrakok.navigation3.browser.buildBrowserHistoryFragment
import com.github.terrakok.navigation3.browser.getBrowserHistoryFragmentName
import com.github.terrakok.navigation3.browser.getBrowserHistoryFragmentParameters

@Composable
actual fun BrowserBackStackSync(backStack: NavBackStack<NavKey>) {
    ChronologicalBrowserNavigation(
        backStack = backStack,
        saveKey = { key ->
            when (key) {
                is Route.Overview -> buildBrowserHistoryFragment("overview")
                is Route.Alerts -> buildBrowserHistoryFragment("alerts")
                is Route.Services -> buildBrowserHistoryFragment("services")
                is Route.Service -> buildBrowserHistoryFragment("service", mapOf("id" to key.id.toString()))
                else -> null
            }
        },
        restoreKey = { fragment ->
            when (getBrowserHistoryFragmentName(fragment)) {
                "overview" -> {
                    Route.Overview
                }

                "alerts" -> {
                    Route.Alerts
                }

                "services" -> {
                    Route.Services
                }

                "service" -> {
                    Route.Service(
                        getBrowserHistoryFragmentParameters(fragment).getValue("id")?.toLong()
                            ?: error("id is required"),
                    )
                }

                else -> {
                    null
                }
            }
        },
    )
}
