package ru.workinprogress.metrik.server.web

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.defaultForFilePath
import io.ktor.server.request.acceptEncoding
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.utils.io.writeFully
import kotlinx.io.buffered
import kotlinx.io.files.SystemFileSystem

/**
 * Отдача дашборда самим сервером — вместо отдельного контейнера с nginx.
 *
 * `staticFiles`/`staticResources` в нативном Ktor недоступны (research §1.3), поэтому руками:
 * kotlinx-io открывает поток, `respondSource` его стримит, MIME берёт `defaultForFilePath`.
 *
 * Сжатия здесь нет и быть не может: `ktor-server-compression` публикуется только под JVM. Вместо
 * него отдаются **заранее сжатые** файлы: если рядом лежит `<файл>.gz` и клиент принимает gzip,
 * уходит он. Сжатие переехало в сборку образа, где делается один раз, а не на каждый запрос.
 */
private const val CHUNK = 64 * 1024

fun Route.webRoutes(assets: WebAssets) {
    get("/{path...}") {
        val requested =
            call.parameters
                .getAll("path")
                .orEmpty()
                .joinToString("/")

        // Выход за корень каталога: '..' не должен уводить к чужим файлам.
        if (requested.split('/').any { it == ".." }) {
            return@get call.respond(HttpStatusCode.NotFound)
        }

        val acceptsGzip = call.request.acceptEncoding()?.contains("gzip") == true
        val wanted = requested.ifEmpty { "index.html" }

        // SPA: неизвестный путь отдаёт оболочку, дальше маршрутизирует само приложение.
        val asset =
            assets.find(wanted, acceptsGzip)
                ?: assets.find("index.html", acceptsGzip)
                ?: return@get call.respond(HttpStatusCode.NotFound)

        // Перепроверка стоит одного условного запроса вместо мегабайтов: без ETag «no-cache»
        // заставлял бы браузер качать бандл целиком на каждый заход.
        call.response.header(HttpHeaders.ETag, asset.etag)
        call.response.header(
            HttpHeaders.CacheControl,
            // Имя .wasm — хэш содержимого: те же байты навсегда. Остальное обязано перепроверяться.
            if (asset.name.endsWith(".wasm")) "public, max-age=31536000, immutable" else "no-cache",
        )
        if (asset.gzipped) call.response.header(HttpHeaders.ContentEncoding, "gzip")

        if (call.request.headers[HttpHeaders.IfNoneMatch]
                ?.split(",")
                ?.any { it.trim() == asset.etag } == true
        ) {
            return@get call.respond(HttpStatusCode.NotModified)
        }

        // Явный цикл по 64 КБ, а не respondSource: тот держит тело целиком, и на двух десятках
        // параллельных скачиваний бандла процесс подбирался к лимиту пода.
        call.respondBytesWriter(ContentType.defaultForFilePath(asset.name), contentLength = asset.size) {
            val chunk = ByteArray(CHUNK)
            SystemFileSystem.source(asset.path).buffered().use { source ->
                while (true) {
                    val read = source.readAtMostTo(chunk, 0, chunk.size)
                    if (read <= 0) break
                    writeFully(chunk, 0, read)
                    flush()
                }
            }
        }
    }
}
