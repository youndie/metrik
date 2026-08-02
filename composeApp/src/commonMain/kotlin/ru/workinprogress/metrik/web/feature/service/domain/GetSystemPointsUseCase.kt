package ru.workinprogress.metrik.web.feature.service.domain

import ru.workinprogress.metrik.api.SystemPoint
import ru.workinprogress.metrik.web.core.domain.UseCase
import ru.workinprogress.metrik.web.core.domain.suspendRunCatching

class GetSystemPointsUseCase(
    private val serviceMetricsRepository: ServiceMetricsRepository,
) : UseCase<GetSystemPointsUseCase.Params, List<SystemPoint>> {
    override suspend fun invoke(params: Params): Result<List<SystemPoint>> =
        suspendRunCatching { serviceMetricsRepository.system(params.serviceId, params.from, params.to) }

    class Params(
        val serviceId: Long,
        val from: Long,
        val to: Long,
    )
}
