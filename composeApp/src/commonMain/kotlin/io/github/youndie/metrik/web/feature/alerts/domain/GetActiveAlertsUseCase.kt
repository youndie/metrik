package io.github.youndie.metrik.web.feature.alerts.domain

import io.github.youndie.metrik.api.AlertView
import io.github.youndie.metrik.web.core.domain.NoParams
import io.github.youndie.metrik.web.core.domain.UseCase
import io.github.youndie.metrik.web.core.domain.suspendRunCatching

class GetActiveAlertsUseCase(
    private val alertsRepository: AlertsRepository,
) : UseCase<NoParams, List<AlertView>> {
    override suspend fun invoke(params: NoParams): Result<List<AlertView>> =
        suspendRunCatching { alertsRepository.active() }
}
