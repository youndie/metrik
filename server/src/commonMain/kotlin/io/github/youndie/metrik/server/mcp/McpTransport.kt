package io.github.youndie.metrik.server.mcp

import io.github.youndie.metrik.server.ServerConfig
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStatelessStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities

internal const val MCP_PATH: String = "/mcp"

/**
 * Ставит MCP-транспорт — и только если токен задан.
 *
 * Авторизация не через `authenticate { }`: `mcpStatelessStreamableHttp` — расширение на
 * [Application], оно ставит собственный роутинг и внутрь блока авторизации не вкладывается.
 *
 * Проверка висит **на узле маршрута**, а не на сравнении строки пути. Раньше это был перехватчик
 * уровня приложения с `if (request.path() != "/mcp") пропустить`, и он судил о запросе не так,
 * как роутер: роутер Ktor выбрасывает пустые сегменты и раскодирует каждый, поэтому `//mcp` и
 * `/%6Dcp` доходили до транспорта, а проверка их не узнавала и пропускала без токена. Теперь
 * «это запрос к MCP?» решает тот же роутер, который выбирает обработчик, — разойтись им не в чем.
 *
 * Транспорт stateless осознанно: инструменты только читают, и сессии, которую стоило бы
 * возобновлять, здесь нет.
 */
fun Application.installMcp(
    config: ServerConfig,
    facade: ToolFacade,
) {
    val token = config.mcpToken ?: return
    val auth = McpAuth(token, config.mcpAllowedHosts)

    // Отказ окончателен и без `finish()`: обработчики маршрута пропускают уже отвеченный вызов,
    // а ответ проверки хоста из SDK, идущей следом в той же фазе, Ktor на отвеченном вызове
    // отбрасывает.
    val authorization =
        createRouteScopedPlugin("McpAuthorization") {
            onCall { call ->
                when (val verdict = auth.check(call)) {
                    is McpAuthResult.InvalidHost -> {
                        call.respondText(
                            """{"error":"invalid host: ${verdict.host}"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.BadRequest,
                        )
                    }

                    McpAuthResult.Unauthorized -> {
                        // Код, а не страница входа: клиент здесь — машина.
                        call.respondText(
                            """{"error":"unauthorized"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.Unauthorized,
                        )
                    }

                    // Пропускаем дальше, в транспорт.
                    McpAuthResult.Allowed -> {}
                }
            }
        }

    // Тот же узел, который SDK получит ниже своим `route(path)`: узел с равным селектором
    // не создаётся заново, а находится (`RoutingNode.createChild`), и всё, что SDK повесит под
    // ним, проходит через эту проверку. Ставится раньше транспорта — значит, и раньше
    // собственной проверки хоста из SDK в той же фазе.
    routing {
        route(MCP_PATH) { install(authorization) }
    }

    mcpStatelessStreamableHttp(
        path = MCP_PATH,
        // Собственная защита SDK от DNS rebinding. Включается только когда хосты заданы: её
        // умолчание разрешает лишь localhost, поэтому на машине разработчика та ошибка, от
        // которой она защищает, не воспроизводится в принципе.
        enableDnsRebindingProtection = config.mcpAllowedHosts.isNotEmpty(),
        allowedHosts = config.mcpAllowedHosts,
    ) {
        // Блок — фабрика, возвращающая Server, а не receiver на нём.
        Server(
            serverInfo = Implementation(name = "metrik", version = "0.1"),
            options =
                ServerOptions(
                    capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = null)),
                ),
        ).apply { registerTools(facade) }
    }
}
