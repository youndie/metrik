package ru.workinprogress.metrik.server.web

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val ROOT = "/tmp/metrik-web-test"

/**
 * Отдача статики. Тест не про «файл вернулся», а про то, чем вместо нас занимался nginx: MIME,
 * валидаторы, сжатые варианты, SPA-фолбэк и обход каталога.
 */
class WebRoutesTest {
    @BeforeTest
    fun setup() {
        val fs = FileSystem.SYSTEM
        fs.createDirectories("$ROOT/nested".toPath())
        fs.write("$ROOT/index.html".toPath()) { writeUtf8("<html>оболочка</html>") }
        fs.write("$ROOT/app.js".toPath()) { writeUtf8("console.log(1)") }
        fs.write("$ROOT/app.js.gz".toPath()) { writeUtf8("сжатая версия") }
        fs.write("$ROOT/abc123.wasm".toPath()) { writeUtf8("wasm-байты") }
        fs.write("$ROOT/nested/deep.txt".toPath()) { writeUtf8("вложенный") }
    }

    @AfterTest
    fun cleanup() {
        FileSystem.SYSTEM.deleteRecursively(ROOT.toPath(), mustExist = false)
    }

    private fun withWeb(block: suspend (HttpClient) -> Unit) =
        testApplication {
            application { routing { webRoutes(WebAssets.scan(ROOT)) } }
            block(client)
        }

    @Test
    fun `a known file should come back with its own content type`() =
        withWeb { client ->
            // When
            val response = client.get("/app.js")

            // Then
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("console.log(1)", response.bodyAsText())
            assertEquals(true, response.headers[HttpHeaders.ContentType]?.contains("javascript"))
        }

    @Test
    fun `a hashed wasm should be cacheable forever and the rest revalidated`() =
        withWeb { client ->
            // Then — имя wasm это хэш содержимого, под тем же именем оно не изменится.
            assertEquals(
                "public, max-age=31536000, immutable",
                client.get("/abc123.wasm").headers[HttpHeaders.CacheControl],
            )
            assertEquals("no-cache", client.get("/app.js").headers[HttpHeaders.CacheControl])
        }

    @Test
    fun `a repeated request with the etag should get 304 without a body`() =
        withWeb { client ->
            // Given
            val etag = assertNotNull(client.get("/app.js").headers[HttpHeaders.ETag])

            // When
            val second = client.get("/app.js") { header(HttpHeaders.IfNoneMatch, etag) }

            // Then — без валидатора «no-cache» означал бы полную перекачку на каждый заход.
            assertEquals(HttpStatusCode.NotModified, second.status)
            assertEquals("", second.bodyAsText())
        }

    @Test
    fun `a client that accepts gzip should get the precompressed twin`() =
        withWeb { client ->
            // When
            val packed = client.get("/app.js") { header(HttpHeaders.AcceptEncoding, "gzip") }

            // Then — сжатие делается на сборке образа: плагина компрессии на нативе нет вовсе.
            assertEquals("gzip", packed.headers[HttpHeaders.ContentEncoding])
        }

    @Test
    fun `an unknown path should fall back to the shell`() =
        withWeb { client ->
            // Then — дальше маршрутизирует само приложение.
            assertEquals("<html>оболочка</html>", client.get("/services/42").bodyAsText())
        }

    @Test
    fun `a nested file should be reachable`() =
        withWeb { client ->
            assertEquals("вложенный", client.get("/nested/deep.txt").bodyAsText())
        }

    @Test
    fun `climbing out of the root should be refused`() =
        withWeb { client ->
            assertEquals(HttpStatusCode.NotFound, client.get("/../etc/hosts").status)
        }
}
