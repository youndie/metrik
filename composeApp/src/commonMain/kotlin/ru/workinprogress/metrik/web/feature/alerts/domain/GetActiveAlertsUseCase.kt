package ru.workinprogress.metrik.web.feature.alerts.domain

import ru.workinprogress.metrik.api.AlertView
import ru.workinprogress.metrik.web.core.domain.NoParams
import ru.workinprogress.metrik.web.core.domain.UseCase
import ru.workinprogress.metrik.web.core.domain.suspendRunCatching

class GetActiveAlertsUseCase(
    private val alertsRepository: AlertsRepository,
) : UseCase<NoParams, List<AlertView>> {
    override suspend fun invoke(params: NoParams): Result<List<AlertView>> = suspendRunCatching { alertsRepository.active() }
}
