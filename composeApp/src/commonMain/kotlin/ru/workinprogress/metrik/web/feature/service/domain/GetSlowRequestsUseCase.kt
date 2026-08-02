package ru.workinprogress.metrik.web.feature.service.domain

import ru.workinprogress.metrik.api.SlowRow
import ru.workinprogress.metrik.web.core.domain.UseCase
import ru.workinprogress.metrik.web.core.domain.suspendRunCatching

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
