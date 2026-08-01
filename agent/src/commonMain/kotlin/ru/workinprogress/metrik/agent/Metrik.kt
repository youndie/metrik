package ru.workinprogress.metrik.agent

import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.CallFailed
import io.ktor.server.application.hooks.CallSetup
import io.ktor.server.application.hooks.MonitoringEvent
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.routing.RoutingRoot
import io.ktor.util.AttributeKey
import ru.workinprogress.metrik.wire.DEFAULT_WINDOW_MS
import ru.workinprogress.metrik.wire.STATUS_NO_RESPONSE
import ru.workinprogress.metrik.wire.encodeStatus
import kotlin.time.TimeSource

/**
 * Конфигурация агента. Поля и дефолты — `docs/services/metrik-agent.md`.
 */
class MetrikConfig {
    /** Логическое имя сервиса. Регистрации нет: имя и есть идентификатор, опечатка заведёт фантом. */
    var service: String = ""

    /** Ingest-key инсталляции metrik (один на установку, не на сервис). */
    var apiKey: String = ""

    /** `host:port` metrik-server. */
    var endpoint: String = ""

    /** Идентификатор инстанса. По умолчанию подставляется имя хоста. */
    var instanceId: String = defaultInstanceId()

    /** Версия релиза; смена значения рисует отметку деплоя на графиках. */
    var release: String? = null

    var windowMs: Long = DEFAULT_WINDOW_MS
    var maxSeries: Int = 200
    var slowSamples: Int = 5
    var systemMetrics: Boolean = true
    var enabled: Boolean = true

    internal var senderFactory: (String) -> MetrikSender = { endpoint -> UdpSender(endpoint) }
}

/** Имя хоста для идентификатора инстанса: в k8s это имя пода. */
internal expect fun defaultInstanceId(): String

private val StartMarkKey = AttributeKey<TimeSource.Monotonic.ValueTimeMark>("MetrikStartMark")
private val RouteTemplateKey = AttributeKey<String>("MetrikRouteTemplate")

/**
 * Плагин-агент. Ставится одной строкой:
 *
 * ```kotlin
 * install(Metrik) {
 *     service = "orders-api"
 *     apiKey = System.getenv("METRIK_KEY")
 *     endpoint = "metrik-server:9999"
 * }
 * ```
 *
 * Инвариант, ради которого написан каждый `try` ниже: **отказ metrik не влияет на целевой сервис.**
 * Нет сервера, не резолвится DNS, переполнена очередь — плагин считает потерю и продолжает работать.
 */
val Metrik =
    createApplicationPlugin(name = "Metrik", createConfiguration = ::MetrikConfig) {
        val config = pluginConfig

        if (!config.enabled) return@createApplicationPlugin

        require(config.service.isNotBlank()) { "Metrik: service is required" }
        require(config.apiKey.isNotBlank()) { "Metrik: apiKey is required" }
        require(config.endpoint.isNotBlank()) { "Metrik: endpoint is required" }

        val agent = MetrikAgent(config, config.senderFactory(config.endpoint))
        agent.start(application)

        application.monitor.subscribe(ApplicationStopping) { agent.stop() }

        // CallSetup, а не хук Metrics: последний в Ktor 3.5 помечен @InternalAPI, хотя ровно для
        // замеров и предназначен. Setup — самая ранняя публичная фаза, разница в наносекундах.
        on(CallSetup) { call ->
            call.attributes.put(StartMarkKey, TimeSource.Monotonic.markNow())
        }

        // Шаблон маршрута доступен только внутри роутинга: атрибут RoutingCall внутренний, а вот
        // событие RoutingCallStarted — публичный API. Атрибуты RoutingCall общие с исходным вызовом,
        // поэтому метка доезжает до ResponseSent.
        on(MonitoringEvent(RoutingRoot.RoutingCallStarted)) { call ->
            call.attributes.put(RouteTemplateKey, pathTemplateOf(call.route.toString()))
        }

        on(ResponseSent) { call ->
            agent.recordSafely(call, call.response.status()?.value ?: STATUS_NO_RESPONSE)
        }

        on(CallFailed) { call, _ ->
            agent.recordSafely(call, STATUS_NO_RESPONSE)
        }
    }

private fun MetrikAgent.recordSafely(
    call: ApplicationCall,
    httpStatus: Int,
) {
    try {
        val mark = call.attributes.getOrNull(StartMarkKey) ?: return
        record(
            method = call.request.local.method.value,
            route = routeTemplateOf(call),
            status = encodeStatus(httpStatus),
            durationMs = mark.elapsedNow().inWholeMilliseconds,
        )
    } catch (_: Throwable) {
        // Метрика не стоит того, чтобы ронять чужой запрос.
    }
}

/**
 * Шаблон маршрута (`/users/{id}`), а не путь запроса (`/users/42`).
 *
 * Сырой путь дал бы неограниченную кардинальность и сделал бы базу бесполезной. Если запрос не
 * сматчился ни на один роут, серия одна на всех — [ROUTE_UNMATCHED].
 */
private fun routeTemplateOf(call: ApplicationCall): String = call.attributes.getOrNull(RouteTemplateKey) ?: ROUTE_UNMATCHED

/**
 * Достаёт из `RoutingNode.toString()` только путь.
 *
 * Ktor печатает туда все селекторы ветки, а не одни сегменты пути: у `get("/users/{id}")`
 * выходит `/users/{id}/(method:GET)`. Метод у нас отдельным полем серии, а `(authenticate …)`,
 * `(header:…)` и прочее к шаблону пути отношения не имеют — всё, что в скобках, отбрасывается.
 */
internal fun pathTemplateOf(raw: String): String {
    val segments = raw.split('/').filter { it.isNotEmpty() && !it.startsWith("(") }
    return if (segments.isEmpty()) "/" else segments.joinToString(separator = "/", prefix = "/")
}
