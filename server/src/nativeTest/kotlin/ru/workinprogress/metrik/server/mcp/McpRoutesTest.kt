package ru.workinprogress.metrik.server.mcp

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import ru.workinprogress.metrik.server.ServerConfig
import ru.workinprogress.metrik.server.module
import ru.workinprogress.metrik.server.openDatabase
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Проверяется не сам протокол MCP — за него отвечает SDK, — а то, кого эндпоинт пускает.
 *
 * Каждый тест здесь соответствует способу, которым такой эндпоинт уже открывался наружу в
 * соседних сервисах.
 */
class McpRoutesTest {
    private val dbPath = "/tmp/metrik-mcp-test.db"
    private val db = openDatabase(dbPath)

    @AfterTest
    fun cleanup() {
        FileSystem.SYSTEM.delete(dbPath.toPath(), mustExist = false)
    }

    private fun config(
        token: String? = "secret",
        hosts: List<String> = emptyList(),
    ) = ServerConfig(
        httpPort = 0,
        udpPort = 0,
        dbPath = dbPath,
        ingestKey = "key",
        mcpToken = token,
        mcpAllowedHosts = hosts,
    )

    private val initialize =
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",""" +
            """"capabilities":{},"clientInfo":{"name":"test","version":"1"}}}"""

    @Test
    fun `no token should mean no endpoint at all`() =
        testApplication {
            // Given — отсутствие настройки обязано давать закрытое состояние, а не открытое.
            application { module(config(token = null), db) }

            // When
            val response =
                client.post("/mcp") {
                    contentType(ContentType.Application.Json)
                    setBody(initialize)
                }

            // Then — не 200 и не 401: роута нет вовсе.
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `a request without a token should be rejected with a code rather than a login page`() =
        testApplication {
            // Given
            application { module(config(), db) }

            // When
            val response =
                client.post("/mcp") {
                    contentType(ContentType.Application.Json)
                    setBody(initialize)
                }

            // Then — машинному клиенту нужен код ошибки; редирект на страницу входа
            // ровно этим ломал такой же эндпоинт у katcher снаружи.
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertNotEquals(HttpStatusCode.Found, response.status)
        }

    @Test
    fun `a wrong token should be rejected`() =
        testApplication {
            // Given
            application { module(config(), db) }

            // When
            val response =
                client.post("/mcp") {
                    header(HttpHeaders.Authorization, "Bearer wrong")
                    contentType(ContentType.Application.Json)
                    setBody(initialize)
                }

            // Then
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `the browser contour's header should not be accepted as authorisation`() =
        testApplication {
            // Given — прокси авторизует по X-Auth-Request-*, и подставить туда можно что угодно.
            // Для браузера это допустимо, для машинного эндпоинта — нет.
            application { module(config(), db) }

            // When
            val response =
                client.post("/mcp") {
                    header("X-Auth-Request-User", "anyone@example.com")
                    contentType(ContentType.Application.Json)
                    setBody(initialize)
                }

            // Then
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `a valid token should be let through`() =
        testApplication {
            // Given
            application { module(config(), db) }

            // When
            val response =
                client.post("/mcp") {
                    header(HttpHeaders.Authorization, "Bearer secret")
                    contentType(ContentType.Application.Json)
                    setBody(initialize)
                }

            // Then
            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `the tool list should carry every tool the agent is expected to know`() =
        testApplication {
            // Given — транспорт, отвечающий 200 на initialize, ещё ничего не говорит о том,
            // что инструменты зарегистрированы.
            application { module(config(), db) }

            // When
            val response =
                client.post("/mcp") {
                    header(HttpHeaders.Authorization, "Bearer secret")
                    header(HttpHeaders.Accept, "application/json, text/event-stream")
                    contentType(ContentType.Application.Json)
                    setBody("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
                }
            val body = response.bodyAsText()

            // Then
            assertEquals(HttpStatusCode.OK, response.status)
            listOf(
                "list_services",
                "service_overview",
                "slow_routes",
                "server_errors",
                "deploys",
                "firing_alerts",
            ).forEach { tool -> assertContains(body, tool) }
        }

    @Test
    fun `a foreign Host should be rejected when hosts are configured`() =
        testApplication {
            // Given — защита от DNS rebinding: браузер жертвы резолвит свой домен в наш адрес.
            // Локально этот класс ошибок не воспроизводится: там хост всегда localhost.
            application { module(config(hosts = listOf("metrik.example.com")), db) }

            // When
            val response =
                client.post("/mcp") {
                    header(HttpHeaders.Authorization, "Bearer secret")
                    header(HttpHeaders.Host, "evil.example.com")
                    contentType(ContentType.Application.Json)
                    setBody(initialize)
                }

            // Then
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
}
