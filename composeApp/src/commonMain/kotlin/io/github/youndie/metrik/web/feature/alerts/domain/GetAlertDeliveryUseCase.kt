package io.github.youndie.metrik.web.feature.alerts.domain

import io.github.youndie.metrik.api.AlertDelivery
import io.github.youndie.metrik.web.core.domain.NoParams
import io.github.youndie.metrik.web.core.domain.UseCase
import io.github.youndie.metrik.web.core.domain.suspendRunCatching

class GetAlertDeliveryUseCase(
    private val alertsRepository: AlertsRepository,
) : UseCase<NoParams, AlertDelivery> {
    override suspend fun invoke(params: NoParams): Result<AlertDelivery> =
        suspendRunCatching { alertsRepository.delivery() }
}
