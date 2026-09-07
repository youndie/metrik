package io.github.youndie.metrik.web.feature.service.domain

import io.github.youndie.metrik.api.RouteRow
import io.github.youndie.metrik.web.core.domain.UseCase
import io.github.youndie.metrik.web.core.domain.suspendRunCatching

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
