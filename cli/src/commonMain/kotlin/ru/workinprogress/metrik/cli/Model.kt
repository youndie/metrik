package ru.workinprogress.metrik.cli

import ru.workinprogress.metrik.api.AlertView
import ru.workinprogress.metrik.api.DeployMarker
import ru.workinprogress.metrik.api.Overview
import ru.workinprogress.metrik.api.RouteRow
import ru.workinprogress.metrik.api.ServiceSummary
import ru.workinprogress.metrik.api.TimeSeries

/**
 * What is on screen right now.
 *
 * One immutable value for the whole client: the terminal is redrawn from it wholesale, so a state
 * split across several holders would show a half-updated screen the moment one of them lagged.
 */
data class UiState(
    val screen: Screen = Screen.SERVICES,
    val services: List<ServiceSummary> = emptyList(),
    val alerts: List<AlertView> = emptyList(),
    val selected: Int = 0,
    val detail: ServiceDetail? = null,
    val loading: Boolean = true,
    /**
     * The last error, kept rather than thrown.
     *
     * A monitoring client that dies when the network blinks is worse than one that says so and
     * keeps the previous numbers on screen — clearly marked as stale.
     */
    val failure: String? = null,
    val staleSince: Long? = null,
) {
    val current: ServiceSummary? get() = services.getOrNull(selected)
}

enum class Screen {
    SERVICES,
    DETAIL,
}

data class ServiceDetail(
    val service: String,
    val overview: Overview,
    val series: TimeSeries,
    val slow: List<RouteRow>,
    val errors: List<RouteRow>,
    val deploys: List<DeployMarker>,
    /**
     * The window that was asked for, carried alongside the answer.
     *
     * The chart needs it: the series only contains windows that reported, so the requested bounds
     * are the only way to know where the silence was.
     */
    val from: Long,
    val to: Long,
)

/** Everything the user can do. Named after intent, not after the key that triggers it. */
sealed interface UiAction {
    data object Up : UiAction

    data object Down : UiAction

    data object Open : UiAction

    data object Back : UiAction

    data object Refresh : UiAction

    data object Quit : UiAction
}
