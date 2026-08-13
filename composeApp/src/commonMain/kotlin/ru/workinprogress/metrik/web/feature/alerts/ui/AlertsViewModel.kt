package ru.workinprogress.metrik.web.feature.alerts.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.workinprogress.metrik.api.AlertRuleView
import ru.workinprogress.metrik.api.AlertView
import ru.workinprogress.metrik.api.ServiceSummary
import ru.workinprogress.metrik.api.isFiring
import ru.workinprogress.metrik.web.core.domain.NoParams
import ru.workinprogress.metrik.web.core.domain.REFRESH_MS
import ru.workinprogress.metrik.web.core.domain.TimeSource
import ru.workinprogress.metrik.web.feature.alerts.domain.GetActiveAlertsUseCase
import ru.workinprogress.metrik.web.feature.alerts.domain.GetAlertHistoryUseCase
import ru.workinprogress.metrik.web.feature.alerts.domain.GetAlertRulesUseCase
import ru.workinprogress.metrik.web.feature.alerts.domain.MuteAlertRuleUseCase
import ru.workinprogress.metrik.web.feature.alerts.domain.SendTestAlertUseCase
import ru.workinprogress.metrik.web.feature.alerts.domain.UnmuteAlertRuleUseCase
import ru.workinprogress.metrik.web.feature.alerts.domain.UpdateAlertRuleUseCase
import ru.workinprogress.metrik.web.feature.services.domain.GetServicesUseCase

/** Состояние кнопки тестового уведомления. */
sealed interface TestAlertState {
    data object Idle : TestAlertState

    data object Sending : TestAlertState

    data class Done(
        val delivered: Boolean,
    ) : TestAlertState

    data class Failed(
        val message: String,
    ) : TestAlertState
}

data class AlertsUiState(
    val firing: List<AlertView> = emptyList(),
    /** Сколько всего правил считается — знаменатель в «горит N из M». */
    val totalRules: Int = 0,
    /** `null` — ещё не загружали; пустой список — история правда пуста. */
    val history: List<AlertView>? = null,
    val historyError: String? = null,
    /** Сервис, чьи пороги показывает панель справа. */
    val rulesService: ServiceSummary? = null,
    val rules: List<AlertRuleView>? = null,
    /** 403 от админского эндпоинта — это «нет доступа», а не «нет правил». */
    val rulesDenied: Boolean = false,
    /** Ошибки мутаций по конкретному правилу — показываются в его карточке, а не общим баннером. */
    val ruleErrors: Map<String, String> = emptyMap(),
    val savingRuleIds: Set<String> = emptySet(),
    val testState: TestAlertState = TestAlertState.Idle,
    val nowMs: Long = 0,
)

sealed interface AlertsUiAction {
    /** Заглушить из «Горят сейчас» — сервис резолвится по имени в алерте. */
    data class MuteAlert(
        val alert: AlertView,
        val minutes: Long,
    ) : AlertsUiAction

    data class UnmuteAlert(
        val alert: AlertView,
    ) : AlertsUiAction

    data class MuteRule(
        val rule: AlertRuleView,
        val minutes: Long,
    ) : AlertsUiAction

    data class UnmuteRule(
        val rule: AlertRuleView,
    ) : AlertsUiAction

    data class SaveRule(
        val rule: AlertRuleView,
    ) : AlertsUiAction

    data object SendTest : AlertsUiAction
}

sealed interface AlertsUiEvent {
    data class ShowError(
        val message: String,
    ) : AlertsUiEvent
}

/**
 * Экран «Алерты»: горящие сейчас, история переходов и пороги правил.
 *
 * История — свой эндпоинт: активные алерты содержат только `FIRING`, и «историей» из них
 * получалась бы копия «Горят сейчас», в которой не может появиться ни одна погасшая запись.
 */
class AlertsViewModel(
    private val getServices: GetServicesUseCase,
    private val getActiveAlerts: GetActiveAlertsUseCase,
    private val getAlertHistory: GetAlertHistoryUseCase,
    private val getAlertRules: GetAlertRulesUseCase,
    private val updateAlertRule: UpdateAlertRuleUseCase,
    private val muteAlertRule: MuteAlertRuleUseCase,
    private val unmuteAlertRule: UnmuteAlertRuleUseCase,
    private val sendTestAlert: SendTestAlertUseCase,
    private val timeSource: TimeSource,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AlertsUiState(nowMs = timeSource.nowMs()))
    val uiState = _uiState.asStateFlow()

    private val _events = Channel<AlertsUiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** Имя сервиса → id: `AlertView` знает только имя, а мутации адресуются по id. */
    private var serviceIdByName: Map<String, Long> = emptyMap()

    init {
        viewModelScope.launch {
            while (true) {
                refresh()
                delay(REFRESH_MS)
            }
        }
        viewModelScope.launch {
            while (true) {
                _uiState.update { it.copy(nowMs = timeSource.nowMs()) }
                delay(1000)
            }
        }
    }

    fun onAction(action: AlertsUiAction) {
        when (action) {
            is AlertsUiAction.MuteAlert -> {
                val id = serviceIdByName[action.alert.service] ?: return
                mutate(action.alert.ruleId, id) { muteAlertRule(MuteAlertRuleUseCase.Params(id, action.alert.ruleId, action.minutes)) }
            }

            is AlertsUiAction.UnmuteAlert -> {
                val id = serviceIdByName[action.alert.service] ?: return
                mutate(action.alert.ruleId, id) { unmuteAlertRule(UnmuteAlertRuleUseCase.Params(id, action.alert.ruleId)) }
            }

            is AlertsUiAction.MuteRule -> {
                val id = _uiState.value.rulesService?.id ?: return
                mutate(action.rule.ruleId, id) { muteAlertRule(MuteAlertRuleUseCase.Params(id, action.rule.ruleId, action.minutes)) }
            }

            is AlertsUiAction.UnmuteRule -> {
                val id = _uiState.value.rulesService?.id ?: return
                mutate(action.rule.ruleId, id) { unmuteAlertRule(UnmuteAlertRuleUseCase.Params(id, action.rule.ruleId)) }
            }

            is AlertsUiAction.SaveRule -> {
                val id = _uiState.value.rulesService?.id ?: return
                _uiState.update { it.copy(savingRuleIds = it.savingRuleIds + action.rule.ruleId) }
                mutate(action.rule.ruleId, id) { updateAlertRule(UpdateAlertRuleUseCase.Params(id, action.rule)) }
            }

            AlertsUiAction.SendTest -> {
                sendTest()
            }
        }
    }

    /**
     * Любая мутация правила возвращает актуальный список правил — сервер отдаёт его в ответе,
     * поэтому перезапрашивать ничего не нужно. Ошибка оседает в карточке правила, а не теряется.
     *
     * Ответ применяется к панели «Пороги» только если мутировали тот же сервис, что в ней открыт:
     * заглушить можно и алерт соседнего сервиса, и его правила панели не принадлежат.
     */
    private fun mutate(
        ruleId: String,
        serviceId: Long,
        block: suspend () -> Result<List<AlertRuleView>>,
    ) {
        viewModelScope.launch {
            block()
                .onSuccess { updated ->
                    _uiState.update {
                        it.copy(
                            rules = if (serviceId == it.rulesService?.id) updated else it.rules,
                            ruleErrors = it.ruleErrors - ruleId,
                            savingRuleIds = it.savingRuleIds - ruleId,
                        )
                    }
                }.onFailure { cause ->
                    val message = cause.message ?: "server error"
                    _uiState.update {
                        it.copy(
                            ruleErrors = it.ruleErrors + (ruleId to message),
                            savingRuleIds = it.savingRuleIds - ruleId,
                        )
                    }
                    _events.send(AlertsUiEvent.ShowError(message))
                }
        }
    }

    private fun sendTest() {
        _uiState.update { it.copy(testState = TestAlertState.Sending) }
        viewModelScope.launch {
            val result =
                sendTestAlert(NoParams).fold(
                    // `delivered == false` показываем как есть: это значит «Telegram не настроен»,
                    // а не «наверное дошло».
                    onSuccess = { delivered -> TestAlertState.Done(delivered) },
                    onFailure = { cause -> TestAlertState.Failed(cause.message ?: "no connection to the server") },
                )
            _uiState.update { it.copy(testState = result) }
        }
    }

    private suspend fun refresh() {
        val services = getServices(GetServicesUseCase.Params()).getOrNull()
        if (services != null) serviceIdByName = services.associate { it.name to it.id }

        val alerts = getActiveAlerts(NoParams).getOrNull()
        val history = getAlertHistory(NoParams)

        // Панель «Пороги» привязана к сервису, а экран «Алерты» верхнеуровневый — берём тот, где
        // сейчас что-то горит, иначе первый по списку: иначе панели было бы нечего показывать.
        val rulesService =
            services?.let { list -> list.firstOrNull { it.firingAlerts.isNotEmpty() } ?: list.firstOrNull() }
                ?: _uiState.value.rulesService

        val rulesResult = rulesService?.let { getAlertRules(GetAlertRulesUseCase.Params(it.id)) }

        _uiState.update { state ->
            state.copy(
                firing = alerts?.filter { it.isFiring } ?: state.firing,
                totalRules =
                    alerts?.let { list ->
                        list
                            .map { it.ruleId }
                            .distinct()
                            .size
                            .coerceAtLeast(list.count { it.isFiring })
                    } ?: state.totalRules,
                history = history.getOrNull() ?: state.history,
                historyError = history.exceptionOrNull()?.let { it.message ?: "could not fetch the history" },
                rulesService = rulesService,
                rules = rulesResult?.getOrNull() ?: state.rules,
                rulesDenied = rulesResult?.isFailure ?: state.rulesDenied,
            )
        }
    }
}
