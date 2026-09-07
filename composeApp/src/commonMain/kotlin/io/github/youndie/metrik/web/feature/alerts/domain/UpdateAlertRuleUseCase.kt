package io.github.youndie.metrik.web.feature.alerts.domain

import io.github.youndie.metrik.api.AlertRuleView
import io.github.youndie.metrik.web.core.domain.UseCase
import io.github.youndie.metrik.web.core.domain.suspendRunCatching

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
