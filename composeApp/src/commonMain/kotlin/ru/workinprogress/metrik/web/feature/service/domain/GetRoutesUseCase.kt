package ru.workinprogress.metrik.web.feature.service.domain

import ru.workinprogress.metrik.api.RouteRow
import ru.workinprogress.metrik.web.core.domain.UseCase
import ru.workinprogress.metrik.web.core.domain.suspendRunCatching

class GetRoutesUseCase(
    private val serviceMetricsRepository: ServiceMetricsRepository,
) : UseCase<GetRoutesUseCase.Params, List<RouteRow>> {
    override suspend fun invoke(params: Params): Result<List<RouteRow>> =
        suspendRunCatching { serviceMetricsRepository.routes(params.serviceId, params.from, params.to) }

    class Params(
        val serviceId: Long,
        val from: Long,
        val to: Long,
    )
}
