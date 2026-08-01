package ru.workinprogress.metrik.agent

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread

/**
 * Чтение маленького системного файла (`/proc/...`, `/sys/...`) без сторонних зависимостей.
 *
 * Агент обязан оставаться лёгким, поэтому здесь голый posix, а не okio: тащить файловую библиотеку
 * ради двух строчек из `/proc` не за что.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun readSmallFile(
    path: String,
    limit: Int = 4096,
): String? =
    memScoped {
        val file = fopen(path, "r") ?: return null

        try {
            val buffer = allocArray<ByteVar>(limit + 1)
            val read = fread(buffer, 1.toULong(), limit.toULong(), file).toInt()
            if (read <= 0) return null

            buffer.readBytes(read).decodeToString()
        } finally {
            fclose(file)
        }
    }
