package ru.workinprogress.metrik.web.feature.alerts.domain

import ru.workinprogress.metrik.api.AlertRuleView
import ru.workinprogress.metrik.web.core.domain.UseCase
import ru.workinprogress.metrik.web.core.domain.suspendRunCatching

class UnmuteAlertRuleUseCase(
    private val alertsRepository: AlertsRepository,
) : UseCase<UnmuteAlertRuleUseCase.Params, List<AlertRuleView>> {
    override suspend fun invoke(params: Params): Result<List<AlertRuleView>> =
        suspendRunCatching { alertsRepository.unmute(params.serviceId, params.ruleId) }

    class Params(
        val serviceId: Long,
        val ruleId: String,
    )
}
