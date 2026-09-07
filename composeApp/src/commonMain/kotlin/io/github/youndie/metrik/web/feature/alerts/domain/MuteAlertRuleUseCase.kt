package io.github.youndie.metrik.web.feature.alerts.domain

import io.github.youndie.metrik.api.AlertRuleView
import io.github.youndie.metrik.web.core.domain.UseCase
import io.github.youndie.metrik.web.core.domain.suspendRunCatching

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
