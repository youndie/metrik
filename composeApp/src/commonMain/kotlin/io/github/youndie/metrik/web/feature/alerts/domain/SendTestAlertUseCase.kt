package io.github.youndie.metrik.web.feature.alerts.domain

import io.github.youndie.metrik.web.core.domain.NoParams
import io.github.youndie.metrik.web.core.domain.UseCase
import io.github.youndie.metrik.web.core.domain.suspendRunCatching

class SendTestAlertUseCase(
    private val alertsRepository: AlertsRepository,
) : UseCase<NoParams, Boolean> {
    override suspend fun invoke(params: NoParams): Result<Boolean> = suspendRunCatching { alertsRepository.sendTest() }
}
