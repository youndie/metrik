package ru.workinprogress.metrik.web.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.workinprogress.metrik.api.ServiceSummary
import ru.workinprogress.metrik.api.isFiring
import ru.workinprogress.metrik.web.core.domain.NoParams
import ru.workinprogress.metrik.web.core.domain.REFRESH_MS
import ru.workinprogress.metrik.web.core.domain.TimeSource
import ru.workinprogress.metrik.web.feature.alerts.domain.GetActiveAlertsUseCase
import ru.workinprogress.metrik.web.feature.services.domain.GetServicesUseCase

/**
 * Состояние оболочки приложения: то, что видно на любом маршруте — список сервисов в рельсе,
 * счётчик горящих алертов, баннер потери связи и «обновлено N назад».
 */
data class AppShellUiState(
    val services: List<ServiceSummary> = emptyList(),
    val firingAlertCount: Int = 0,
    val error: String? = null,
    /** Отдельно от [error]: различает «ещё не получили первый ответ» и «сервисов правда нет». */
    val loaded: Boolean = false,
    val lastSuccessAt: Long? = null,
    val nowMs: Long = 0,
) {
    val updatedAgoLabel: String
        get() = lastSuccessAt?.let { "updated ${relativeAgo(nowMs, it)} ago" } ?: "polling…"
}

/**
 * ViewModel оболочки. Действий у неё нет: всё, что делает пользователь на этом уровне, — это
 * навигация, а она живёт в back stack (см. `navigation/Route.kt`), не в состоянии экрана.
 *
 * Зависит от юзкейсов двух фич — это нормальный кросс-фичевый экран (см. скилл `client-feature-impl`).
 */
class AppShellViewModel(
    private val getServices: GetServicesUseCase,
    private val getActiveAlerts: GetActiveAlertsUseCase,
    private val timeSource: TimeSource,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AppShellUiState(nowMs = timeSource.nowMs()))
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                refresh()
                delay(REFRESH_MS)
            }
        }
        // Секундный тик — только ради надписи «обновлено N назад»: данные он не запрашивает.
        viewModelScope.launch {
            while (true) {
                _uiState.update { it.copy(nowMs = timeSource.nowMs()) }
                delay(1000)
            }
        }
    }

    private suspend fun refresh() {
        // Рельсу нужен «живой» срез, а не выбранный на «Обзоре» диапазон: период не передаём —
        // сервер сам возьмёт последние пять минут.
        val services = getServices(GetServicesUseCase.Params())
        val alerts = getActiveAlerts(NoParams)
        val failure = services.exceptionOrNull() ?: alerts.exceptionOrNull()
        _uiState.update { state ->
            state.copy(
                services = services.getOrNull() ?: state.services,
                firingAlertCount = alerts.getOrNull()?.count { it.isFiring } ?: state.firingAlertCount,
                error = failure?.let { it.message ?: "could not fetch the data" },
                loaded = true,
                lastSuccessAt = if (failure == null) timeSource.nowMs() else state.lastSuccessAt,
            )
        }
    }
}
