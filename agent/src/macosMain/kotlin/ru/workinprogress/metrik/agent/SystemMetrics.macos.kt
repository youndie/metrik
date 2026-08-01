package ru.workinprogress.metrik.agent

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.RUSAGE_SELF
import platform.posix._SC_NPROCESSORS_ONLN
import platform.posix.getrusage
import platform.posix.rusage
import platform.posix.sysconf

/**
 * macOS — платформа разработки, не деплоя, поэтому реализация намеренно грубая: `getrusage`
 * даёт пиковый RSS и CPU-время, числа тредов там нет.
 *
 * `ru_maxrss` на macOS в байтах (на Linux — в килобайтах); именно поэтому эта реализация отдельная,
 * а не общая для всего натива.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun readSystemMetrics(): SystemReading =
    memScoped {
        val usage = alloc<rusage>()
        if (getrusage(RUSAGE_SELF, usage.ptr) != 0) {
            return SystemReading(null, null, null, null)
        }

        val userMillis = usage.ru_utime.tv_sec * 1000L + usage.ru_utime.tv_usec / 1000L
        val systemMillis = usage.ru_stime.tv_sec * 1000L + usage.ru_stime.tv_usec / 1000L

        SystemReading(
            memoryUsedBytes = usage.ru_maxrss,
            memoryLimitBytes = null,
            cpuMillis = userMillis + systemMillis,
            threads = null,
        )
    }

@OptIn(ExperimentalForeignApi::class)
actual fun availableProcessors(): Int = sysconf(_SC_NPROCESSORS_ONLN).takeIf { it > 0 }?.toInt() ?: 1
