package ru.workinprogress.metrik.server.query

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import ru.workinprogress.metrik.api.Api
import ru.workinprogress.metrik.api.Step
import ru.workinprogress.metrik.api.TestNotificationResult
import ru.workinprogress.metrik.server.ServerConfig
import ru.workinprogress.metrik.server.alert.AlertWorker
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private const val HEADER_USER = "X-Auth-Request-User"
private const val HEADER_EMAIL = "X-Auth-Request-Email"

/**
 * Аутентификации у metrik своей нет: доверяем reverse proxy, как katcher.
 *
 * Отсюда требование, которое должно быть написано в README крупно: **без прокси перед UI ставить
 * metrik нельзя** — иначе дашборд открыт всем, кто дотянулся до порта.
 */
private suspend fun RoutingContext.authenticated(): String? {
    val user = call.request.headers[HEADER_USER]

    if (user.isNullOrBlank()) {
        call.respond(HttpStatusCode.Unauthorized, "missing $HEADER_USER")
        return null
    }

    return user
}

/**
 * Админ — это email из `METRIK_ADMINS`. Список пуст → админ любой прошедший прокси: инсталляция
 * принадлежит одной команде, и разделение ролей внутри неё по умолчанию лишняя церемония.
 */
private suspend fun RoutingContext.admin(config: ServerConfig): String? {
    val user = authenticated() ?: return null
    if (config.admins.isEmpty()) return user

    val email = call.request.headers[HEADER_EMAIL]
    if (email == null || email !in config.admins) {
        call.respond(HttpStatusCode.Forbidden, "admin access required")
        return null
    }

    return user
}

@OptIn(ExperimentalTime::class)
private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS

/** Полуфабрикат периода: `from range to withDefaultSpan span`. */
private class RangeBuilder(
    val from: Long?,
    val to: Long?,
)

private infix fun Long?.range(to: Long?): RangeBuilder = RangeBuilder(this, to)

@OptIn(ExperimentalTime::class)
private infix fun RangeBuilder.withDefaultSpan(spanMs: Long): Pair<Long, Long> {
    val end = to ?: Clock.System.now().toEpochMilliseconds()
    return (from ?: (end - spanMs)) to end
}

private fun stepOf(raw: String?): Step =
    when (raw?.lowercase()) {
        "1h", "hour" -> Step.HOUR
        "1d", "day" -> Step.DAY
        else -> Step.MINUTE
    }

fun Route.queryRoutes(
    query: QueryService,
    config: ServerConfig,
) {
    get<Api.Services> { resource ->
        authenticated() ?: return@get
        // Дефолт — последние пять минут: столько нужно, чтобы «сейчас» было честным при минутном окне.
        val (from, to) = resource.from range resource.to withDefaultSpan 5 * MINUTE_MS
        call.respond(query.services(from, to))
    }

    get<Api.Services.ById.Overview> { resource ->
        authenticated() ?: return@get
        val (from, to) = resource.from range resource.to withDefaultSpan HOUR_MS
        call.respond(query.overview(resource.parent.id, from, to))
    }

    get<Api.Services.ById.TimeSeries> { resource ->
        authenticated() ?: return@get
        val (from, to) = resource.from range resource.to withDefaultSpan HOUR_MS
        call.respond(query.timeSeries(resource.parent.id, from, to, stepOf(resource.step)))
    }

    get<Api.Services.ById.Routes> { resource ->
        authenticated() ?: return@get
        val (from, to) = resource.from range resource.to withDefaultSpan HOUR_MS
        call.respond(query.routes(resource.parent.id, from, to))
    }

    get<Api.Services.ById.System> { resource ->
        authenticated() ?: return@get
        val (from, to) = resource.from range resource.to withDefaultSpan HOUR_MS
        call.respond(query.system(resource.parent.id, from, to))
    }

    get<Api.Services.ById.Slow> { resource ->
        authenticated() ?: return@get
        val (from, to) = resource.from range resource.to withDefaultSpan DAY_MS
        call.respond(query.slow(resource.parent.id, from, to))
    }

    get<Api.Services.ById.Deploys> { resource ->
        authenticated() ?: return@get
        val (from, to) = resource.from range resource.to withDefaultSpan HOUR_MS
        call.respond(query.deploys(resource.parent.id, from, to))
    }
}

fun Route.adminRoutes(
    service: AdminService,
    config: ServerConfig,
) {
    get<Api.Admin.Service.Alerts> { resource ->
        admin(config) ?: return@get
        call.respond(service.rules(resource.parent.id))
    }

    put<Api.Admin.Service.Alerts> { resource ->
        admin(config) ?: return@put
        service.updateRule(resource.parent.id, call.receive())
        call.respond(service.rules(resource.parent.id))
    }

    put<Api.Admin.Service.Alerts.Mute> { resource ->
        admin(config) ?: return@put
        val id = resource.parent.parent.id
        service.mute(id, resource.rule, nowMs() + (resource.minutes ?: 60L) * MINUTE_MS)
        call.respond(service.rules(id))
    }

    delete<Api.Admin.Service.Alerts.Mute> { resource ->
        admin(config) ?: return@delete
        val id = resource.parent.parent.id
        service.unmute(id, resource.rule)
        call.respond(service.rules(id))
    }

    delete<Api.Admin.Service> { resource ->
        admin(config) ?: return@delete
        service.deleteService(resource.id)
        call.respond(HttpStatusCode.NoContent)
    }
}

/**
 * Тестовое уведомление: единственный способ убедиться, что настройка Telegram рабочая,
 * не дожидаясь настоящей аварии.
 */
fun Route.alertTestRoute(
    alerts: AlertWorker,
    config: ServerConfig,
) {
    post<Api.Admin.AlertsTest> {
        admin(config) ?: return@post
        call.respond(TestNotificationResult(delivered = alerts.sendTest()))
    }
}

fun Route.alertRoutes(alerts: AlertWorker) {
    get<Api.Alerts> {
        authenticated() ?: return@get
        call.respond(alerts.active())
    }

    get<Api.Alerts.History> {
        authenticated() ?: return@get
        call.respond(alerts.history())
    }
}
