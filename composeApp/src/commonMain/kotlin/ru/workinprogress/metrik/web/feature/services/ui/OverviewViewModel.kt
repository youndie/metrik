package ru.workinprogress.metrik.web.feature.services.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.workinprogress.metrik.api.AlertView
import ru.workinprogress.metrik.api.ServiceSummary
import ru.workinprogress.metrik.api.isFiring
import ru.workinprogress.metrik.web.core.domain.NoParams
import ru.workinprogress.metrik.web.core.domain.REFRESH_MS
import ru.workinprogress.metrik.web.core.domain.Range
import ru.workinprogress.metrik.web.core.domain.TimeSource
import ru.workinprogress.metrik.web.feature.alerts.domain.GetActiveAlertsUseCase
import ru.workinprogress.metrik.web.feature.service.domain.GetTimeSeriesUseCase
import ru.workinprogress.metrik.web.feature.services.domain.GetServicesUseCase
import ru.workinprogress.metrik.web.ui.ChartPoint
import ru.workinprogress.metrik.web.ui.toChart

data class OverviewUiState(
    val range: Range = Range.HOUR,
    val services: List<ServiceSummary> = emptyList(),
    val firingAlerts: List<AlertView> = emptyList(),
    val sparklines: Map<Long, List<ChartPoint>> = emptyMap(),
    /**
     * Не сбрасывается при смене диапазона: иначе экран на мгновение схлопывался бы в спиннер
     * вместо того, чтобы держать старые цифры до прихода новых.
     */
    val loaded: Boolean = false,
)

sealed interface OverviewUiAction {
    data class SelectRange(
        val range: Range,
    ) : OverviewUiAction
}

/**
 * «Обзор» сам себе хозяин по данным: в отличие от рельса (тот всегда показывает «живой» срез за
 * последние пять минут) здесь есть переключатель диапазона, и он обязан реально перезапрашивать
 * список сервисов и спарклайны за выбранный период, а не просто перекрашивать кнопку.
 */
class OverviewViewModel(
    private val getServices: GetServicesUseCase,
    private val getTimeSeries: GetTimeSeriesUseCase,
    private val getActiveAlerts: GetActiveAlertsUseCase,
    private val timeSource: TimeSource,
) : ViewModel() {
    private val _uiState = MutableStateFlow(OverviewUiState())
    val uiState = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        restartPolling()
    }

    fun onAction(action: OverviewUiAction) {
        when (action) {
            is OverviewUiAction.SelectRange -> {
                if (action.range == _uiState.value.range) return
                _uiState.update { it.copy(range = action.range) }
                restartPolling()
            }
        }
    }

    /**
     * Отмена предыдущей загрузки обязательна: без неё ответ за старый диапазон, пришедший позже
     * запроса за новым, молча перезапишет свежие данные.
     */
    private fun restartPolling() {
        loadJob?.cancel()
        loadJob =
            viewModelScope.launch {
                while (true) {
                    refresh(_uiState.value.range)
                    delay(REFRESH_MS)
                }
            }
    }

    private suspend fun refresh(range: Range) {
        val to = timeSource.nowMs()
        val from = to - range.ms

        val services = getServices(GetServicesUseCase.Params(from, to)).getOrNull()
        val alerts = getActiveAlerts(NoParams).getOrNull()

        val sparklines =
            services?.let { list ->
                coroutineScope {
                    list
                        .map { service ->
                            service.id to
                                async {
                                    getTimeSeries(GetTimeSeriesUseCase.Params(service.id, from, to, range.step))
                                        .getOrNull()
                                        ?.points
                                        ?.toChart { it.requestsPerSecond }
                                        .orEmpty()
                                }
                        }.associate { (id, deferred) -> id to deferred.await() }
                }
            }

        _uiState.update { state ->
            state.copy(
                services = services ?: state.services,
                firingAlerts = alerts?.filter { it.isFiring } ?: state.firingAlerts,
                sparklines = sparklines ?: state.sparklines,
                loaded = true,
            )
        }
    }
}
