package ru.workinprogress.metrik.server.query

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import ru.workinprogress.metrik.server.ServerConfig
import ru.workinprogress.metrik.server.module
import ru.workinprogress.metrik.server.openDatabase
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class QueryRoutesTest {
    private val dbPath = "/tmp/metrik-routes-test.db"
    private val db = openDatabase(dbPath)

    @AfterTest
    fun cleanup() {
        FileSystem.SYSTEM.delete(dbPath.toPath(), mustExist = false)
    }

    private fun config(admins: Set<String> = emptySet()) =
        ServerConfig(
            httpPort = 0,
            udpPort = 0,
            dbPath = dbPath,
            ingestKey = "key",
            admins = admins,
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
