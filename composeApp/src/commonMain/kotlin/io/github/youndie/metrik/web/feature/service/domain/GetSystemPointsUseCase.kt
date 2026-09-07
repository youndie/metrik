package io.github.youndie.metrik.web.feature.service.domain

import io.github.youndie.metrik.api.SystemPoint
import io.github.youndie.metrik.web.core.domain.UseCase
import io.github.youndie.metrik.web.core.domain.suspendRunCatching

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
