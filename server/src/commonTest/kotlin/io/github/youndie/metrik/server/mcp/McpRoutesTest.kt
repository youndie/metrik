package io.github.youndie.metrik.server.mcp

import io.github.youndie.metrik.server.ServerConfig
import io.github.youndie.metrik.server.TestDatabase
import io.github.youndie.metrik.server.module
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodedPath
import io.ktor.server.testing.testApplication
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * Проверяется не сам протокол MCP — за него отвечает SDK — и не правила охраны: какой заголовок
 * считается токеном, что с чужим `Host`, что с заголовками браузерного контура, — это проверяет
 * kore-mcp своими тестами, у него же эти правила и живут.
 *
 * Здесь — то, чего kore-mcp за metrik проверить не может: что настройки сервера доходят до охраны,
 * что инструменты зарегистрированы, и что охрана стоит там, куда роутер на самом деле приводит
 * запрос, — на собранном `module`, а не на заготовке.
 */
class McpRoutesTest {
    private val database = TestDatabase("mcp")
    private val db = database.db

    @AfterTest
    fun cleanup() = database.close()

    private fun config(
        token: String? = "secret",
        hosts: List<String> = emptyList(),
    ) = ServerConfig(
        httpPort = 0,
        udpPort = 0,
        dbPath = database.path,
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
            // Given — отсутствие настройки обязано давать закрытое состояние, а не открытое. Само
            // правило — в kore-mcp; здесь проверяется, что `METRIK_MCP_TOKEN` до него доходит.
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
                "time_series",
                "system_metrics",
                "deploys",
                "alert_rules",
                "firing_alerts",
            ).forEach { tool -> assertContains(body, tool) }
        }

    /**
     * Роутер Ktor выбрасывает пустые сегменты пути и раскодирует каждый, поэтому все эти пути
     * попадают в тот же обработчик, что и `/mcp`. Строка `request.path()` у них при этом другая, и
     * проверка, сравнивавшая её с `/mcp`, пропускала такие запросы в транспорт без токена.
     *
     * Перенесено из #54 и оставлено здесь, хотя у kore-mcp есть свой такой же тест: свойство
     * держится на том, как metrik ставит эндпоинт рядом со своими маршрутами, и проверять его надо
     * на собранном сервере.
     */
    private val foldedPaths = listOf("//mcp", "///mcp", "/%6Dcp", "/%6dcp")

    @Test
    fun `a path the router folds into the endpoint should still need the token`() =
        testApplication {
            // Given
            application { module(config(), db) }

            foldedPaths.forEach { path ->
                // When — путь задаётся закодированным: иначе клиент нормализовал бы его сам и
                // тест проверял бы ровно `/mcp`.
                val response =
                    client.post {
                        url { encodedPath = path }
                        contentType(ContentType.Application.Json)
                        setBody(initialize)
                    }

                // Then
                assertEquals(HttpStatusCode.Unauthorized, response.status, path)
            }
        }

    @Test
    fun `the folded paths should reach the transport with a valid token`() =
        testApplication {
            // Given — контроль к тесту выше: без него его 401 мог бы значить «путь до роутера
            // не дошёл таким, как задан», а не «проверка его узнала».
            application { module(config(), db) }

            foldedPaths.forEach { path ->
                // When
                val response =
                    client.post {
                        url { encodedPath = path }
                        header(HttpHeaders.Authorization, "Bearer secret")
                        contentType(ContentType.Application.Json)
                        setBody(initialize)
                    }

                // Then
                assertEquals(HttpStatusCode.OK, response.status, path)
            }
        }

    @Test
    fun `a foreign Host should be rejected when hosts are configured`() =
        testApplication {
            // Given — защита от DNS rebinding: браузер жертвы резолвит свой домен в наш адрес.
            // Правило — в kore-mcp; здесь проверяется, что `METRIK_MCP_ALLOWED_HOSTS` до него
            // доходит. Локально этот класс ошибок не воспроизводится: там хост всегда localhost.
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
