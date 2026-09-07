package io.github.youndie.metrik.cli

import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.github.youndie.metrik.api.AlertRuleView
import io.github.youndie.metrik.api.AlertView
import io.github.youndie.metrik.api.DeployMarker
import io.github.youndie.metrik.api.Overview
import io.github.youndie.metrik.api.RouteRow
import io.github.youndie.metrik.api.ServiceSummary
import io.github.youndie.metrik.api.TimeSeries
import io.github.youndie.metrik.wire.MetrikJson

/**
 * The client's whole conversation with metrik.
 *
 * It speaks MCP rather than the HTTP read API, and that is a decision worth stating: `/api` is
 * behind the browser-authenticating proxy, so reaching it from a terminal would mean inventing a
 * second way in and guarding it forever. `/mcp` is already the door for machines.
 *
 * The payloads are decoded into the very same DTOs from `:shared` that the dashboard uses — the
 * tools encode them with [MetrikJson] on the other side. No second copy of the contract exists,
 * and a renamed field breaks the build rather than the screen.
 */
class MetrikClient(
    private val config: CliConfig,
) {
    private val http = HttpClient(Curl)

    private val transport =
        StreamableHttpClientTransport(
            client = http,
            url = config.url + "/mcp",
            requestBuilder = {
                header(HttpHeaders.Authorization, "Bearer ${config.token}")
            },
        )

    private val client =
        Client(clientInfo = Implementation(name = "metrik-cli", version = "0.1"))

    suspend fun connect() {
        client.connect(transport)
    }

    suspend fun close() {
        runCatching { client.close() }
        http.close()
    }

    suspend fun services(): List<ServiceSummary> = decode(call("list_services"))

    suspend fun firingAlerts(): List<AlertView> = decode(call("firing_alerts"))

    suspend fun overview(
        service: String,
        from: Long,
        to: Long,
    ): Overview = decode(call("service_overview", window(service, from, to)))

    suspend fun timeSeries(
        service: String,
        from: Long,
        to: Long,
    ): TimeSeries = decode(call("time_series", window(service, from, to)))

    suspend fun slowRoutes(
        service: String,
        from: Long,
        to: Long,
        limit: Int,
    ): List<RouteRow> = decode(call("slow_routes", window(service, from, to) + ("limit" to limit)))

    suspend fun serverErrors(
        service: String,
        from: Long,
        to: Long,
        limit: Int,
    ): List<RouteRow> = decode(call("server_errors", window(service, from, to) + ("limit" to limit)))

    suspend fun alertRules(service: String): List<AlertRuleView> =
        decode(call("alert_rules", mapOf("service" to service)))

    suspend fun deploys(
        service: String,
        from: Long,
        to: Long,
    ): List<DeployMarker> = decode(call("deploys", window(service, from, to)))

    private fun window(
        service: String,
        from: Long,
        to: Long,
    ): Map<String, Any?> =
        mapOf(
            "service" to service,
            "from" to from,
            "to" to to,
        )

    private suspend fun call(
        tool: String,
        arguments: Map<String, Any?> = emptyMap(),
    ): String {
        val result = client.callTool(name = tool, arguments = arguments)
        val text = result.content.filterIsInstance<TextContent>().joinToString("") { it.text.orEmpty() }

        // The server reports a bad argument by flagging the result, not by failing the call.
        // Swallowing that flag would turn "no such service" into "nothing is wrong".
        if (result.isError == true) error(text.ifBlank { "$tool failed" })

        return text
    }

    private inline fun <reified T> decode(payload: String): T = MetrikJson.decodeFromString(payload)
}
