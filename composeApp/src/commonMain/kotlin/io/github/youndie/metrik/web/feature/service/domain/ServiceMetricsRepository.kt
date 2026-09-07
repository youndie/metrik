package io.github.youndie.metrik.web.feature.service.domain

import io.github.youndie.metrik.api.RouteRow
import io.github.youndie.metrik.api.SlowRow
import io.github.youndie.metrik.api.Step
import io.github.youndie.metrik.api.SystemPoint
import io.github.youndie.metrik.api.TimeSeries

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
