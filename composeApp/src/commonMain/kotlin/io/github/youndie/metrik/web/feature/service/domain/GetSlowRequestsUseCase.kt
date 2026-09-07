package io.github.youndie.metrik.web.feature.service.domain

import io.github.youndie.metrik.api.SlowRow
import io.github.youndie.metrik.web.core.domain.UseCase
import io.github.youndie.metrik.web.core.domain.suspendRunCatching

class GetSlowRequestsUseCase(
    private val serviceMetricsRepository: ServiceMetricsRepository,
) : UseCase<GetSlowRequestsUseCase.Params, List<SlowRow>> {
    override suspend fun invoke(params: Params): Result<List<SlowRow>> =
        suspendRunCatching { serviceMetricsRepository.slow(params.serviceId, params.from, params.to) }

    class Params(
        val serviceId: Long,
        val from: Long,
        val to: Long,
    )
}
