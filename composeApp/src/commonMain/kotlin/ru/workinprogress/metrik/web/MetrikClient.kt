package ru.workinprogress.metrik.web

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import ru.workinprogress.metrik.api.AlertView
import ru.workinprogress.metrik.api.Overview
import ru.workinprogress.metrik.api.RouteRow
import ru.workinprogress.metrik.api.ServiceSummary
import ru.workinprogress.metrik.api.SlowRow
import ru.workinprogress.metrik.api.Step
import ru.workinprogress.metrik.api.SystemPoint
import ru.workinprogress.metrik.api.TimeSeries

/**
 * Клиент к metrik-server.
 *
 * Base URL пустой в проде: дашборд и API стоят за одним хостом, разводка по путям на ingress.
 * Для отладки на desktop его задаёт [MetrikClient] явно.
 */
class MetrikClient(
    private val baseUrl: String = "",
    private val client: HttpClient =
        HttpClient {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        },
) {
    suspend fun services(): List<ServiceSummary> = client.get("$baseUrl/api/services").body()

    suspend fun overview(
        serviceId: Long,
        from: Long,
        to: Long,
    ): Overview = client.get("$baseUrl/api/services/$serviceId/overview") { range(from, to) }.body()

    suspend fun timeSeries(
        serviceId: Long,
        from: Long,
        to: Long,
        step: Step,
    ): TimeSeries =
        client
            .get("$baseUrl/api/services/$serviceId/timeseries") {
                range(from, to)
                parameter(
                    "step",
                    when (step) {
                        Step.MINUTE -> "1m"
                        Step.HOUR -> "1h"
                        Step.DAY -> "1d"
                    },
                )
            }.body()

    suspend fun routes(
        serviceId: Long,
        from: Long,
        to: Long,
    ): List<RouteRow> = client.get("$baseUrl/api/services/$serviceId/routes") { range(from, to) }.body()

    suspend fun slow(serviceId: Long): List<SlowRow> = client.get("$baseUrl/api/services/$serviceId/slow").body()

    suspend fun system(
        serviceId: Long,
        from: Long,
        to: Long,
    ): List<SystemPoint> = client.get("$baseUrl/api/services/$serviceId/system") { range(from, to) }.body()

    suspend fun alerts(): List<AlertView> = client.get("$baseUrl/api/alerts").body()

    suspend fun alertHistory(): List<AlertView> = client.get("$baseUrl/api/alerts/history").body()
}

private fun io.ktor.client.request.HttpRequestBuilder.range(
    from: Long,
    to: Long,
) {
    parameter("from", from)
    parameter("to", to)
}
