package io.github.youndie.metrik.web.feature.service.domain

import io.github.youndie.metrik.api.Step
import io.github.youndie.metrik.api.TimeSeries
import io.github.youndie.metrik.web.core.domain.UseCase
import io.github.youndie.metrik.web.core.domain.suspendRunCatching

class GetTimeSeriesUseCase(
    private val serviceMetricsRepository: ServiceMetricsRepository,
) : UseCase<GetTimeSeriesUseCase.Params, TimeSeries> {
    override suspend fun invoke(params: Params): Result<TimeSeries> =
        suspendRunCatching {
            serviceMetricsRepository.timeSeries(
                params.serviceId,
                params.from,
                params.to,
                params.step,
            )
        }

    class Params(
        val serviceId: Long,
        val from: Long,
        val to: Long,
        val step: Step,
    )
}
