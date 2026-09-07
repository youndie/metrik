package io.github.youndie.metrik.web.feature.alerts.domain

import io.github.youndie.metrik.api.AlertRuleView
import io.github.youndie.metrik.web.core.domain.UseCase
import io.github.youndie.metrik.web.core.domain.suspendRunCatching

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
