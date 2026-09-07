package io.github.youndie.metrik.web.feature.alerts.domain

import io.github.youndie.metrik.api.AlertRuleView
import io.github.youndie.metrik.web.core.domain.UseCase
import io.github.youndie.metrik.web.core.domain.suspendRunCatching

class GetAlertRulesUseCase(
    private val alertsRepository: AlertsRepository,
) : UseCase<GetAlertRulesUseCase.Params, List<AlertRuleView>> {
    override suspend fun invoke(params: Params): Result<List<AlertRuleView>> =
        suspendRunCatching { alertsRepository.rules(params.serviceId) }

    class Params(
        val serviceId: Long,
    )
}
