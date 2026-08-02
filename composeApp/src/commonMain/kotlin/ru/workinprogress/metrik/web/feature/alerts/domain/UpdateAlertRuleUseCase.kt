package ru.workinprogress.metrik.web.feature.alerts.domain

import ru.workinprogress.metrik.api.AlertRuleView
import ru.workinprogress.metrik.web.core.domain.UseCase
import ru.workinprogress.metrik.web.core.domain.suspendRunCatching

class UpdateAlertRuleUseCase(
    private val alertsRepository: AlertsRepository,
) : UseCase<UpdateAlertRuleUseCase.Params, List<AlertRuleView>> {
    override suspend fun invoke(params: Params): Result<List<AlertRuleView>> =
        suspendRunCatching { alertsRepository.updateRule(params.serviceId, params.rule) }

    class Params(
        val serviceId: Long,
        val rule: AlertRuleView,
    )
}
