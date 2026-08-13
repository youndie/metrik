package ru.workinprogress.metrik.server.mcp

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStatelessStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import ru.workinprogress.metrik.server.ServerConfig

internal const val MCP_PATH: String = "/mcp"

/**
 * Ставит MCP-транспорт — и только если токен задан.
 *
 * Авторизация сделана перехватчиком, а не `authenticate { }`: `mcpStatelessStreamableHttp` —
 * расширение на [Application], оно ставит собственный роутинг и внутрь блока авторизации не
 * вкладывается.
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

    intercept(ApplicationCallPipeline.Plugins) {
        // В перехватчике вызов лежит в `context`, а не в `call`: это pipeline, а не роут.
        val call = context
        if (call.request.path() != MCP_PATH) return@intercept

        when (val verdict = auth.check(call)) {
            is McpAuthResult.InvalidHost -> {
                call.respondText(
                    """{"error":"invalid host: ${verdict.host}"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                finish()
            }

            McpAuthResult.Unauthorized -> {
                // Код, а не страница входа: клиент здесь — машина.
                call.respondText(
                    """{"error":"unauthorized"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.Unauthorized,
                )
                finish()
            }

            // Пропускаем дальше, в транспорт.
            McpAuthResult.Allowed -> {}
        }
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
