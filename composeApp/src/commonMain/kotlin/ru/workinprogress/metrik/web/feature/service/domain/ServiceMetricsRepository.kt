package ru.workinprogress.metrik.web.feature.service.domain

import ru.workinprogress.metrik.api.RouteRow
import ru.workinprogress.metrik.api.SlowRow
import ru.workinprogress.metrik.api.Step
import ru.workinprogress.metrik.api.SystemPoint
import ru.workinprogress.metrik.api.TimeSeries

/** Метрики одного сервиса — по одной операции на вкладку экрана сервиса плюс общий таймсерис. */
interface ServiceMetricsRepository {
    suspend fun timeSeries(
        serviceId: Long,
        from: Long,
        to: Long,
        step: Step,
    ): TimeSeries

    suspend fun routes(
        serviceId: Long,
        from: Long,
        to: Long,
    ): List<RouteRow>

    suspend fun slow(
        serviceId: Long,
        from: Long,
        to: Long,
    ): List<SlowRow>

    suspend fun system(
        serviceId: Long,
        from: Long,
        to: Long,
    ): List<SystemPoint>
}
