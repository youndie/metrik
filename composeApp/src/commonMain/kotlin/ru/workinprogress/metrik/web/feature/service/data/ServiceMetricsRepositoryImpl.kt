package ru.workinprogress.metrik.web.feature.service.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.resources.get
import ru.workinprogress.metrik.api.Api
import ru.workinprogress.metrik.api.RouteRow
import ru.workinprogress.metrik.api.SlowRow
import ru.workinprogress.metrik.api.Step
import ru.workinprogress.metrik.api.SystemPoint
import ru.workinprogress.metrik.api.TimeSeries
import ru.workinprogress.metrik.web.feature.service.domain.ServiceMetricsRepository

class ServiceMetricsRepositoryImpl(
    private val client: HttpClient,
) : ServiceMetricsRepository {
    private fun service(serviceId: Long) = Api.Services.ById(id = serviceId)

    override suspend fun timeSeries(
        serviceId: Long,
        from: Long,
        to: Long,
        step: Step,
    ): TimeSeries =
        client
            .get(
                Api.Services.ById.TimeSeries(
                    parent = service(serviceId),
                    from = from,
                    to = to,
                    step = step.wire,
                ),
            ).body()

    override suspend fun routes(
        serviceId: Long,
        from: Long,
        to: Long,
    ): List<RouteRow> = client.get(Api.Services.ById.Routes(service(serviceId), from, to)).body()

    /**
     * Медленные сэмплы за период (M-85 — раньше сервер всегда отдавал последние 24 часа, теперь
     * период выбирается так же, как на остальных вкладках сервиса).
     */
    override suspend fun slow(
        serviceId: Long,
        from: Long,
        to: Long,
    ): List<SlowRow> = client.get(Api.Services.ById.Slow(service(serviceId), from, to)).body()

    override suspend fun system(
        serviceId: Long,
        from: Long,
        to: Long,
    ): List<SystemPoint> = client.get(Api.Services.ById.System(service(serviceId), from, to)).body()
}

/** Как шаг агрегации называется в контракте — сервер разбирает именно эти строки. */
private val Step.wire: String
    get() =
        when (this) {
            Step.MINUTE -> "1m"
            Step.HOUR -> "1h"
            Step.DAY -> "1d"
        }
