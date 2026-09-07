package io.github.youndie.metrik.web.feature.alerts.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.resources.delete
import io.ktor.client.plugins.resources.get
import io.ktor.client.plugins.resources.post
import io.ktor.client.plugins.resources.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.github.youndie.metrik.api.AlertRuleView
import io.github.youndie.metrik.api.AlertView
import io.github.youndie.metrik.api.Api
import io.github.youndie.metrik.api.TestNotificationResult
import io.github.youndie.metrik.web.feature.alerts.domain.AlertsRepository

class AlertsRepositoryImpl(
    private val client: HttpClient,
) : AlertsRepository {
    private fun alertsOf(serviceId: Long) = Api.Admin.Service.Alerts(Api.Admin.Service(id = serviceId))

    override suspend fun active(): List<AlertView> = client.get(Api.Alerts()).body()

    override suspend fun history(): List<AlertView> = client.get(Api.Alerts.History()).body()

    override suspend fun rules(serviceId: Long): List<AlertRuleView> = client.get(alertsOf(serviceId)).body()

    override suspend fun updateRule(
        serviceId: Long,
        rule: AlertRuleView,
    ): List<AlertRuleView> =
        client
            .put(alertsOf(serviceId)) {
                contentType(ContentType.Application.Json)
                setBody(rule)
            }.body()

    override suspend fun mute(
        serviceId: Long,
        ruleId: String,
        minutes: Long,
    ): List<AlertRuleView> =
        client
            .put(
                Api.Admin.Service.Alerts
                    .Mute(alertsOf(serviceId), ruleId, minutes),
            ).body()

    override suspend fun unmute(
        serviceId: Long,
        ruleId: String,
    ): List<AlertRuleView> =
        client
            .delete(
                Api.Admin.Service.Alerts
                    .Mute(alertsOf(serviceId), ruleId),
            ).body()

    override suspend fun sendTest(): Boolean =
        client.post(Api.Admin.AlertsTest()).body<TestNotificationResult>().delivered
}
