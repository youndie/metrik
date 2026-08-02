package ru.workinprogress.metrik.web.feature.service.domain

import ru.workinprogress.metrik.api.Step
import ru.workinprogress.metrik.api.TimeSeries
import ru.workinprogress.metrik.web.core.domain.UseCase
import ru.workinprogress.metrik.web.core.domain.suspendRunCatching

class GetTimeSeriesUseCase(
    private val serviceMetricsRepository: ServiceMetricsRepository,
) : UseCase<GetTimeSeriesUseCase.Params, TimeSeries> {
    override suspend fun invoke(params: Params): Result<TimeSeries> =
        suspendRunCatching { serviceMetricsRepository.timeSeries(params.serviceId, params.from, params.to, params.step) }

    class Params(
        val serviceId: Long,
        val from: Long,
        val to: Long,
        val step: Step,
    )
}
