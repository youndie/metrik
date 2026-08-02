package ru.workinprogress.metrik.web.feature.alerts.domain

import ru.workinprogress.metrik.web.core.domain.NoParams
import ru.workinprogress.metrik.web.core.domain.UseCase
import ru.workinprogress.metrik.web.core.domain.suspendRunCatching

class SendTestAlertUseCase(
    private val alertsRepository: AlertsRepository,
) : UseCase<NoParams, Boolean> {
    override suspend fun invoke(params: NoParams): Result<Boolean> = suspendRunCatching { alertsRepository.sendTest() }
}
