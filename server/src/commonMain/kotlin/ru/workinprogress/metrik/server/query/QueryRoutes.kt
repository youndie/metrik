package ru.workinprogress.metrik.server.query

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import ru.workinprogress.metrik.api.Step
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

@OptIn(ExperimentalTime::class)
private fun ApplicationCall.range(defaultSpanMs: Long = 60 * 60 * 1000): Pair<Long, Long> {
    val now = Clock.System.now().toEpochMilliseconds()
    val to = request.queryParameters["to"]?.toLongOrNull() ?: now
    val from = request.queryParameters["from"]?.toLongOrNull() ?: (to - defaultSpanMs)
    return from to to
}

private fun ApplicationCall.step(): Step =
    when (request.queryParameters["step"]?.lowercase()) {
        "1h", "hour" -> Step.HOUR
        "1d", "day" -> Step.DAY
        else -> Step.MINUTE
    }

fun Route.queryRoutes(
    query: QueryService,
    config: ServerConfig,
) {
    get("/services") {
        authenticated() ?: return@get
        // Дефолт — последние пять минут: столько нужно, чтобы «сейчас» было честным при минутном окне.
        val (from, to) = call.range(defaultSpanMs = 5 * 60 * 1000)
        call.respond(query.services(from, to))
    }

    route("/services/{id}") {
        get("/overview") {
            authenticated() ?: return@get
            val id = call.serviceId() ?: return@get
            val (from, to) = call.range()
            call.respond(query.overview(id, from, to))
        }

        get("/timeseries") {
            authenticated() ?: return@get
            val id = call.serviceId() ?: return@get
            val (from, to) = call.range()
            call.respond(query.timeSeries(id, from, to, call.step()))
        }

        get("/routes") {
            authenticated() ?: return@get
            val id = call.serviceId() ?: return@get
            val (from, to) = call.range()
            call.respond(query.routes(id, from, to))
        }

        get("/system") {
            authenticated() ?: return@get
            val id = call.serviceId() ?: return@get
            val (from, to) = call.range()
            call.respond(query.system(id, from, to))
        }

        get("/slow") {
            authenticated() ?: return@get
            val id = call.serviceId() ?: return@get
            val (from, to) = call.range(defaultSpanMs = 24 * 60 * 60 * 1000)
            call.respond(query.slow(id, from, to))
        }

        get("/deploys") {
            authenticated() ?: return@get
            val id = call.serviceId() ?: return@get
            val (from, to) = call.range()
            call.respond(query.deploys(id, from, to))
        }
    }
}

fun Route.adminRoutes(
    service: AdminService,
    config: ServerConfig,
) {
    route("/admin/services/{id}") {
        get("/alerts") {
            admin(config) ?: return@get
            val id = call.serviceId() ?: return@get
            call.respond(service.rules(id))
        }

        put("/alerts") {
            admin(config) ?: return@put
            val id = call.serviceId() ?: return@put
            service.updateRule(id, call.receive())
            call.respond(service.rules(id))
        }

        put("/alerts/{rule}/mute") {
            admin(config) ?: return@put
            val id = call.serviceId() ?: return@put
            val rule = call.parameters["rule"] ?: return@put call.respond(HttpStatusCode.BadRequest)
            val minutes = call.request.queryParameters["minutes"]?.toLongOrNull() ?: 60L
            service.mute(id, rule, nowMs() + minutes * 60_000)
            call.respond(service.rules(id))
        }

        delete("/alerts/{rule}/mute") {
            admin(config) ?: return@delete
            val id = call.serviceId() ?: return@delete
            val rule = call.parameters["rule"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            service.unmute(id, rule)
            call.respond(service.rules(id))
        }

        delete {
            admin(config) ?: return@delete
            val id = call.serviceId() ?: return@delete
            service.deleteService(id)
            call.respond(HttpStatusCode.NoContent)
        }
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
    post("/admin/alerts/test") {
        admin(config) ?: return@post
        val delivered = alerts.sendTest()
        call.respond(TestNotificationResult(delivered = delivered))
    }
}

@kotlinx.serialization.Serializable
data class TestNotificationResult(
    val delivered: Boolean,
)

private suspend fun ApplicationCall.serviceId(): Long? {
    val id = parameters["id"]?.toLongOrNull()
    if (id == null) respond(HttpStatusCode.BadRequest, "service id must be a number")
    return id
}

fun Route.alertRoutes(alerts: AlertWorker) {
    get("/alerts") {
        authenticated() ?: return@get
        call.respond(alerts.active())
    }

    get("/alerts/history") {
        authenticated() ?: return@get
        call.respond(alerts.history())
    }
}
