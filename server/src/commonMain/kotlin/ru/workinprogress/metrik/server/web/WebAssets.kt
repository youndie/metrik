package ru.workinprogress.metrik.server.web

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** Один файл дашборда, каким его отдаёт сервер. */
class WebAsset(
    val path: Path,
    /** Логическое имя без `.gz` — по нему выбирается MIME и политика кэширования. */
    val name: String,
    val size: Long,
    val etag: String,
    val gzipped: Boolean,
)

/**
 * Каталог статики, снятый один раз на старте.
 *
 * ETag считается по содержимому, а не берётся из метаданных файла: у `FileMetadata` в kotlinx-io
 * нет времени модификации, так что `Last-Modified` взять неоткуда. Без валидатора браузер на
 * каждый заход перекачивал бы бандл целиком — `no-cache` означает «перепроверь», а перепроверять
 * ему было бы нечем.
 *
 * Читать на старте не жалко: файлы вшиты в образ и за время жизни процесса не меняются, а обход
 * идёт потоком по 64 КБ, без загрузки бандла в память целиком.
 */
class WebAssets(
    private val byName: Map<String, WebAsset>,
) {
    fun find(
        name: String,
        acceptsGzip: Boolean,
    ): WebAsset? {
        if (acceptsGzip) byName["$name.gz"]?.let { return it }
        return byName[name]
    }

    val size: Int get() = byName.size

    companion object {
        private const val CHUNK = 64 * 1024

        fun scan(root: String): WebAssets {
            val found = mutableMapOf<String, WebAsset>()
            walk(Path(root), prefix = "") { relative, path, size ->
                val gzipped = relative.endsWith(".gz")
                found[relative] =
                    WebAsset(
                        path = path,
                        name = if (gzipped) relative.removeSuffix(".gz") else relative,
                        size = size,
                        etag = "\"${contentHash(path)}\"",
                        gzipped = gzipped,
                    )
            }
            return WebAssets(found)
        }

        private fun walk(
            dir: Path,
            prefix: String,
            visit: (relative: String, path: Path, size: Long) -> Unit,
        ) {
            for (entry in SystemFileSystem.list(dir)) {
                val meta = SystemFileSystem.metadataOrNull(entry) ?: continue
                val relative = if (prefix.isEmpty()) entry.name else "$prefix/${entry.name}"
                when {
                    meta.isDirectory -> walk(entry, relative, visit)
                    meta.isRegularFile -> visit(relative, entry, meta.size)
                }
            }
        }

        /**
         * FNV-1a по содержимому. Не криптография и ею быть не должна: ETag обязан меняться вместе
         * с файлом, а не сопротивляться подбору.
         */
        private fun contentHash(path: Path): String {
            var hash = -3750763034362895579L // FNV offset basis
            val buffer = ByteArray(CHUNK)
            SystemFileSystem.source(path).buffered().use { source ->
                while (true) {
                    val read = source.readAtMostTo(buffer, 0, buffer.size)
                    if (read <= 0) break
                    for (i in 0 until read) {
                        hash = hash xor buffer[i].toLong()
                        hash *= 1099511628211L // FNV prime
                    }
                }
            }
            return hash.toULong().toString(16)
        }
    }
}
