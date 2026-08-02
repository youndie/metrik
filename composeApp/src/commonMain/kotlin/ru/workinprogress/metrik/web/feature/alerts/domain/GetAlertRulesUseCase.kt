package ru.workinprogress.metrik.web.feature.alerts.domain

import ru.workinprogress.metrik.api.AlertRuleView
import ru.workinprogress.metrik.web.core.domain.UseCase
import ru.workinprogress.metrik.web.core.domain.suspendRunCatching

class GetAlertRulesUseCase(
    private val alertsRepository: AlertsRepository,
) : UseCase<GetAlertRulesUseCase.Params, List<AlertRuleView>> {
    override suspend fun invoke(params: Params): Result<List<AlertRuleView>> =
        suspendRunCatching { alertsRepository.rules(params.serviceId) }

    class Params(
        val serviceId: Long,
    )
}
