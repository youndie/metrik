package ru.workinprogress.metrik.web.core.data

import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.resources.Resources
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import ru.workinprogress.metrik.wire.MetrikJson

/**
 * HTTP-клиент к metrik-server.
 *
 * [baseUrl] пустой в проде: дашборд и API стоят за одним хостом, разводка по путям на ingress.
 * Для отладки на desktop его задаёт точка входа.
 *
 * [user] тоже нужен только для отладки: в проде заголовок `X-Auth-Request-User` проставляет
 * reverse proxy, а при локальном запуске прокси нет и сервер отвечал бы 401 на всё.
 *
 * `Resources` — та же половина контракта, что и на сервере: пути берутся из классов `Api` в
 * `:shared`, руками здесь не собирается ни один URL.
 */
fun metrikHttpClient(
    baseUrl: String = "",
    user: String? = null,
): HttpClient =
    HttpClient {
        // Тот же `MetrikJson`, что и на сервере, а не своя копия настроек: настройки сериализации —
        // такая же часть контракта, как имена полей. Разъехавшись, они ломают разбор целого ответа.
        install(ContentNegotiation) {
            json(MetrikJson)
        }
        install(Resources)
        defaultRequest {
            if (baseUrl.isNotEmpty()) url(baseUrl)
            if (user != null) header("X-Auth-Request-User", user)
        }
    }
