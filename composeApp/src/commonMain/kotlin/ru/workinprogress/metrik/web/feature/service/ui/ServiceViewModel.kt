package ru.workinprogress.metrik.web.feature.service.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.workinprogress.metrik.api.RouteRow
import ru.workinprogress.metrik.api.ServiceSummary
import ru.workinprogress.metrik.api.SlowRow
import ru.workinprogress.metrik.api.SystemPoint
import ru.workinprogress.metrik.api.TimeSeries
import ru.workinprogress.metrik.web.core.domain.REFRESH_MS
import ru.workinprogress.metrik.web.core.domain.Range
import ru.workinprogress.metrik.web.core.domain.TimeSource
import ru.workinprogress.metrik.web.feature.service.domain.GetRoutesUseCase
import ru.workinprogress.metrik.web.feature.service.domain.GetSlowRequestsUseCase
import ru.workinprogress.metrik.web.feature.service.domain.GetSystemPointsUseCase
import ru.workinprogress.metrik.web.feature.service.domain.GetTimeSeriesUseCase
import ru.workinprogress.metrik.web.feature.services.domain.DeleteServiceUseCase
import ru.workinprogress.metrik.web.feature.services.domain.GetServicesUseCase

/** Вкладки экрана сервиса. Порядок совпадает с макетом. */
enum class ServiceTab(
    val title: String,
) {
    CHARTS("Графики"),
    ROUTES("Маршруты"),
    SLOW("Медленные"),
    SYSTEM("Система"),
}

data class ServiceUiState(
    /**
     * `null`, пока не пришёл первый список сервисов: экран открывается по id из маршрута и своё имя
     * узнаёт только оттуда. Показывать в это время пустую шапку честнее, чем выдуманное имя.
     */
    val service: ServiceSummary? = null,
    val tab: ServiceTab = ServiceTab.CHARTS,
    val range: Range = Range.HOUR,
    val series: TimeSeries? = null,
    val routes: List<RouteRow> = emptyList(),
    val slow: List<SlowRow> = emptyList(),
    val system: List<SystemPoint> = emptyList(),
    val loaded: Boolean = false,
    /** Секундный тик для «N назад» в медленных запросах. */
    val nowMs: Long = 0,
    /** Показан ли запрос подтверждения на удаление: операция необратима, одного клика мало. */
    val deleteRequested: Boolean = false,
    val deleting: Boolean = false,
    val deleteError: String? = null,
)

sealed interface ServiceUiAction {
    data class SelectTab(
        val tab: ServiceTab,
    ) : ServiceUiAction

    data class SelectRange(
        val range: Range,
    ) : ServiceUiAction

    /** Первый клик — только вопрос; сервис вместе со всей историей удаляет [ConfirmDelete]. */
    data object RequestDelete : ServiceUiAction

    data object CancelDelete : ServiceUiAction

    data object ConfirmDelete : ServiceUiAction
}

sealed interface ServiceUiEvent {
    /** Сервиса больше нет — экрану нечего показывать, вызывающий обязан уйти назад. */
    data object Deleted : ServiceUiEvent
}

class ServiceViewModel(
    private val serviceId: Long,
    private val getServices: GetServicesUseCase,
    private val getTimeSeries: GetTimeSeriesUseCase,
    private val getRoutes: GetRoutesUseCase,
    private val getSlowRequests: GetSlowRequestsUseCase,
    private val getSystemPoints: GetSystemPointsUseCase,
    private val deleteService: DeleteServiceUseCase,
    private val timeSource: TimeSource,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ServiceUiState(nowMs = timeSource.nowMs()))
    val uiState = _uiState.asStateFlow()

    private val _events = Channel<ServiceUiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var loadJob: Job? = null

    init {
        restartPolling()
        viewModelScope.launch {
            while (true) {
                _uiState.update { it.copy(nowMs = timeSource.nowMs()) }
                delay(1000)
            }
        }
    }

    fun onAction(action: ServiceUiAction) {
        when (action) {
            is ServiceUiAction.SelectTab -> {
                _uiState.update { it.copy(tab = action.tab) }
            }

            is ServiceUiAction.SelectRange -> {
                if (action.range == _uiState.value.range) return
                _uiState.update { it.copy(range = action.range, loaded = false) }
                restartPolling()
            }

            ServiceUiAction.RequestDelete -> {
                _uiState.update { it.copy(deleteRequested = true, deleteError = null) }
            }

            ServiceUiAction.CancelDelete -> {
                _uiState.update { it.copy(deleteRequested = false) }
            }

            ServiceUiAction.ConfirmDelete -> {
                confirmDelete()
            }
        }
    }

    private fun confirmDelete() {
        _uiState.update { it.copy(deleting = true, deleteError = null) }
        viewModelScope.launch {
            deleteService(DeleteServiceUseCase.Params(serviceId))
                .onSuccess {
                    // Опрос останавливаем сразу: сервиса больше нет, и его запросы будут отвечать 404.
                    loadJob?.cancel()
                    _events.send(ServiceUiEvent.Deleted)
                }.onFailure { cause ->
                    _uiState.update {
                        it.copy(
                            deleting = false,
                            deleteRequested = false,
                            deleteError = cause.message ?: "не удалось удалить сервис",
                        )
                    }
                }
        }
    }

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

        // Шапка экрана (имя, инстансы, горящие правила) живёт в сводке сервиса — своего эндпоинта
        // «один сервис» в контракте нет, поэтому берём его из общего списка.
        val service = getServices(GetServicesUseCase.Params()).getOrNull()?.firstOrNull { it.id == serviceId }
        val series = getTimeSeries(GetTimeSeriesUseCase.Params(serviceId, from, to, range.step)).getOrNull()
        val routes = getRoutes(GetRoutesUseCase.Params(serviceId, from, to)).getOrNull()
        // M-85: медленные запросы тоже за выбранный период, а не всегда за последние 24 часа.
        val slow = getSlowRequests(GetSlowRequestsUseCase.Params(serviceId, from, to)).getOrNull()
        val system = getSystemPoints(GetSystemPointsUseCase.Params(serviceId, from, to)).getOrNull()

        _uiState.update { state ->
            state.copy(
                service = service ?: state.service,
                series = series ?: state.series,
                routes = routes ?: state.routes,
                slow = slow ?: state.slow,
                system = system ?: state.system,
                loaded = true,
            )
        }
    }
}
