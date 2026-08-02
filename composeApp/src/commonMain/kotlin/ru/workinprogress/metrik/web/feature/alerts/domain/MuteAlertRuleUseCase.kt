package ru.workinprogress.metrik.web.feature.alerts.domain

import ru.workinprogress.metrik.api.AlertRuleView
import ru.workinprogress.metrik.web.core.domain.UseCase
import ru.workinprogress.metrik.web.core.domain.suspendRunCatching

class MuteAlertRuleUseCase(
    private val alertsRepository: AlertsRepository,
) : UseCase<MuteAlertRuleUseCase.Params, List<AlertRuleView>> {
    override suspend fun invoke(params: Params): Result<List<AlertRuleView>> =
        suspendRunCatching { alertsRepository.mute(params.serviceId, params.ruleId, params.minutes) }

    class Params(
        val serviceId: Long,
        val ruleId: String,
        val minutes: Long,
    )
}
