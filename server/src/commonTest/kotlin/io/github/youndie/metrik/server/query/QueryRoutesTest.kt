package io.github.youndie.metrik.server.query

import io.github.youndie.metrik.api.AlertDelivery
import io.github.youndie.metrik.server.ServerConfig
import io.github.youndie.metrik.server.TestDatabase
import io.github.youndie.metrik.server.module
import io.github.youndie.metrik.wire.MetrikJson
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class QueryRoutesTest {
    private val database = TestDatabase("routes")
    private val db = database.db

    @AfterTest
    fun cleanup() = database.close()

    private fun config(
        admins: Set<String> = emptySet(),
        telegramToken: String? = null,
        telegramChatId: String? = null,
    ) = ServerConfig(
        httpPort = 0,
        udpPort = 0,
        dbPath = database.path,
        ingestKey = "key",
        admins = admins,
        telegramToken = telegramToken,
        telegramChatId = telegramChatId,
    )

    @Test
    fun `reading without the proxy header should be rejected`() =
        testApplication {
            // Given — своей аутентификации у metrik нет, доверяем reverse proxy.
            application { module(config(), db) }

            // When
            val response = client.get("/api/services")

            // Then
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `reading with the proxy header should be allowed`() =
        testApplication {
            // Given
            application { module(config(), db) }

            // When
            val response = client.get("/api/services") { header("X-Auth-Request-User", "alice") }

            // Then
            assertEquals(HttpStatusCode.OK, response.status)
        }

    /** M-132: дашборд писал «TELEGRAM» всегда — канал теперь говорит сервер, который его настроил. */
    @Test
    fun `alert delivery should name no channel when telegram is not configured`() =
        testApplication {
            // Given — ни токена, ни чата: уведомителя нет.
            application { module(config(), db) }

            // When
            val response = client.get("/api/alerts/delivery") { header("X-Auth-Request-User", "alice") }

            // Then
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                AlertDelivery(channel = null, cooldownMinutes = 30),
                MetrikJson.decodeFromString(response.bodyAsText()),
            )
        }

    @Test
    fun `alert delivery should name telegram when it is configured`() =
        testApplication {
            // Given
            application { module(config(telegramToken = "token", telegramChatId = "42"), db) }

            // When
            val response = client.get("/api/alerts/delivery") { header("X-Auth-Request-User", "alice") }

            // Then
            assertEquals(
                AlertDelivery(channel = "telegram", cooldownMinutes = 30),
                MetrikJson.decodeFromString(response.bodyAsText()),
            )
        }

    @Test
    fun `health should stay open for the orchestrator`() =
        testApplication {
            // Given
            application { module(config(), db) }

            // When
            val response = client.get("/health")

            // Then
            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `an admin route should reject users outside the admin list`() =
        testApplication {
            // Given
            application { module(config(admins = setOf("boss@example.com")), db) }

            // When
            val response =
                client.get("/api/admin/services/1/alerts") {
                    header("X-Auth-Request-User", "alice")
                    header("X-Auth-Request-Email", "alice@example.com")
                }

            // Then
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `an empty admin list should make every authenticated user an admin`() =
        testApplication {
            // Given — инсталляция принадлежит одной команде: роли внутри неё лишняя церемония.
            application { module(config(), db) }

            // When
            val response =
                client.get("/api/admin/services/1/alerts") { header("X-Auth-Request-User", "alice") }

            // Then
            assertEquals(HttpStatusCode.OK, response.status)
        }
}
