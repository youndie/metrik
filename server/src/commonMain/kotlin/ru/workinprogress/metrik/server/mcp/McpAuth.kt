package ru.workinprogress.metrik.server.mcp

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header

/**
 * Авторизация MCP-эндпоинта.
 *
 * Здесь всё — выводы из того, как этот же эндпоинт уже уезжал в прод у соседних сервисов:
 *
 * - **без токена не ставится ничего.** Отсутствие настройки даёт закрытое состояние, а не
 *   открытое: ни роута, ни секрета в чарте, ни обхода ingress;
 * - **доверие браузерного контура не переиспользуется.** Прокси, авторизующий по
 *   `X-Auth-Request-*`, принимает любую заявленную личность. Для браузера это допустимо, для
 *   машинного эндпоинта — подарок тому, кто дотянулся до порта;
 * - **`Host` проверяется.** Это защита от DNS rebinding, и локально она не проверяется никак:
 *   на машине разработчика хост всегда и есть localhost;
 * - **401 остаётся 401.** Глобальный редирект на `/login` отдаёт машинному клиенту страницу входа
 *   вместо кода ошибки — ровно этим ломался эндпоинт katcher снаружи.
 */
class McpAuth(
    private val token: String,
    private val allowedHosts: List<String>,
) {
    fun check(call: ApplicationCall): McpAuthResult {
        val host = call.request.header(HttpHeaders.Host)?.substringBefore(':')

        if (allowedHosts.isNotEmpty() && host != null && allowedHosts.none { it.equals(host, ignoreCase = true) }) {
            return McpAuthResult.InvalidHost(host)
        }

        val header = call.request.header(HttpHeaders.Authorization) ?: return McpAuthResult.Unauthorized
        val presented = header.removePrefix("Bearer ").trim()

        return if (constantTimeEquals(presented, token)) McpAuthResult.Allowed else McpAuthResult.Unauthorized
    }

    /**
     * Длина секретом не является, значение — является.
     *
     * Сравнение не останавливается на первом различии: обычное `==` выходит раньше на непохожем
     * префиксе, и по времени ответа токен подбирается посимвольно.
     */
    private fun constantTimeEquals(
        a: String,
        b: String,
    ): Boolean {
        if (a.length != b.length) return false

        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)

        return diff == 0
    }
}

sealed interface McpAuthResult {
    data object Allowed : McpAuthResult

    data object Unauthorized : McpAuthResult

    data class InvalidHost(
        val host: String,
    ) : McpAuthResult
}
