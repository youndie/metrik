package ru.workinprogress.metrik.web

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.workinprogress.metrik.api.AlertRuleView
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
 * Для отладки на desktop его задаёт вызывающий.
 *
 * [user] тоже нужен только для отладки: в проде заголовок `X-Auth-Request-User` проставляет
 * reverse proxy, а при локальном запуске прокси нет и сервер отвечал бы 401 на всё.
 */
class MetrikClient(
    private val baseUrl: String = "",
    user: String? = null,
    private val client: HttpClient =
        HttpClient {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            if (user != null) {
                defaultRequest { header("X-Auth-Request-User", user) }
            }
        },
) {
    /**
     * Список сервисов за период. `from`/`to` — миллисекунды эпохи; без них сервер сам берёт
     * последние 5 минут (см. `docs/api/endpoint-query.md`) — так вызывает рельс, которому нужен
     * «живой» список, а не срез за выбранный на Обзоре диапазон.
     */
    suspend fun services(
        from: Long? = null,
        to: Long? = null,
    ): List<ServiceSummary> =
        client
            .get("$baseUrl/api/services") {
                if (from != null && to != null) range(from, to)
            }.body()

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

    /**
     * Медленные сэмплы за период (M-85 — раньше сервер всегда отдавал последние 24 часа, теперь
     * период выбирается так же, как на остальных вкладках сервиса).
     */
    suspend fun slow(
        serviceId: Long,
        from: Long,
        to: Long,
    ): List<SlowRow> = client.get("$baseUrl/api/services/$serviceId/slow") { range(from, to) }.body()

    suspend fun system(
        serviceId: Long,
        from: Long,
        to: Long,
    ): List<SystemPoint> = client.get("$baseUrl/api/services/$serviceId/system") { range(from, to) }.body()

    suspend fun alerts(): List<AlertView> = client.get("$baseUrl/api/alerts").body()

    suspend fun alertHistory(): List<AlertView> = client.get("$baseUrl/api/alerts/history").body()

    /**
     * Пороги правил для сервиса — экран «Алерты», панель «Пороги». Админский эндпоинт: без
     * `METRIK_ADMINS` доступен всем, кто прошёл прокси, иначе кто угодно вне списка получит 403 —
     * вызывающий код обязан обрабатывать это как «нет доступа», а не как «нет данных».
     */
    suspend fun adminAlertRules(serviceId: Long): List<AlertRuleView> = client.get("$baseUrl/api/admin/services/$serviceId/alerts").body()

    /**
     * Переопределяет порог правила для сервиса (M-82) — единственная мутация не из раздела
     * алертов-заглушек в UI. Сервер возвращает актуальный список правил, поэтому отдельного
     * пере запроса после сохранения не нужно.
     */
    suspend fun updateAlertRule(
        serviceId: Long,
        rule: AlertRuleView,
    ): List<AlertRuleView> =
        client
            .put("$baseUrl/api/admin/services/$serviceId/alerts") {
                contentType(ContentType.Application.Json)
                setBody(rule)
            }.body()

    /**
     * Заглушает уведомления по правилу на [minutes] (M-81). Заглушает только доставку — правило
     * продолжает считаться и гореть в UI (`AlertRuleView.mutedUntil`, `AlertView.mutedUntil`).
     */
    suspend fun muteAlertRule(
        serviceId: Long,
        ruleId: String,
        minutes: Long,
    ): List<AlertRuleView> =
        client
            .put("$baseUrl/api/admin/services/$serviceId/alerts/$ruleId/mute") {
                parameter("minutes", minutes)
            }.body()

    suspend fun unmuteAlertRule(
        serviceId: Long,
        ruleId: String,
    ): List<AlertRuleView> = client.delete("$baseUrl/api/admin/services/$serviceId/alerts/$ruleId/mute").body()

    /**
     * Тестовое уведомление в Telegram (M-81) — единственный способ узнать, что доставка настроена,
     * не дожидаясь настоящей аварии. `delivered == false` вызывающий код обязан показать как есть:
     * это значит, что Telegram не настроен или недоступен, а не что сообщение «наверное дошло».
     */
    suspend fun sendTestAlert(): Boolean = client.post("$baseUrl/api/admin/alerts/test").body<TestNotificationResult>().delivered
}

/** Локальная копия ответа `POST /admin/alerts/test` — DTO живёт только на сервере, трогать server/ нельзя. */
@Serializable
private data class TestNotificationResult(
    val delivered: Boolean,
)

private fun io.ktor.client.request.HttpRequestBuilder.range(
    from: Long,
    to: Long,
) {
    parameter("from", from)
    parameter("to", to)
}
