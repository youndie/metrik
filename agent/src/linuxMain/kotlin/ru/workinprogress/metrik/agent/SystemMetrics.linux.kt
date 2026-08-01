package ru.workinprogress.metrik.agent

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix._SC_CLK_TCK
import platform.posix._SC_NPROCESSORS_ONLN
import platform.posix._SC_PAGESIZE
import platform.posix.sysconf

/**
 * Linux: всё берётся из `/proc`, никаких зависимостей.
 *
 * Важное отличие от JVM, которое нельзя терять в UI: здесь **RSS**, а не heap. У нативного процесса
 * нет «максимума heap» — вместо него подставляется лимит cgroup, если он читается.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun readSystemMetrics(): SystemReading {
    val statm = readSmallFile("/proc/self/statm")?.trim()?.split(" ")
    val pageSize = sysconf(_SC_PAGESIZE).takeIf { it > 0 } ?: 4096L
    val residentBytes = statm?.getOrNull(1)?.toLongOrNull()?.times(pageSize)

    val stat = readSmallFile("/proc/self/stat")
    val clockTicks = sysconf(_SC_CLK_TCK).takeIf { it > 0 } ?: 100L

    // Поле comm может содержать пробелы и скобки, поэтому разбор начинается после последней ')'.
    val statFields = stat?.substringAfterLast(')')?.trim()?.split(" ")
    val utime = statFields?.getOrNull(11)?.toLongOrNull()
    val stime = statFields?.getOrNull(12)?.toLongOrNull()
    val threads = statFields?.getOrNull(17)?.toIntOrNull()

    val cpuMillis =
        if (utime != null && stime != null) (utime + stime) * 1000L / clockTicks else null

    return SystemReading(
        memoryUsedBytes = residentBytes,
        memoryLimitBytes = readCgroupMemoryLimit(),
        cpuMillis = cpuMillis,
        threads = threads,
    )
}

@OptIn(ExperimentalForeignApi::class)
actual fun availableProcessors(): Int = sysconf(_SC_NPROCESSORS_ONLN).takeIf { it > 0 }?.toInt() ?: 1

/**
 * Лимит памяти контейнера. cgroup v2 пишет `max` вместо числа, когда лимита нет, — это `null`,
 * а не «ноль» и не «бесконечность».
 */
private fun readCgroupMemoryLimit(): Long? {
    val v2 = readSmallFile("/sys/fs/cgroup/memory.max")?.trim()
    if (v2 != null && v2 != "max") return v2.toLongOrNull()

    val v1 = readSmallFile("/sys/fs/cgroup/memory/memory.limit_in_bytes")?.trim()?.toLongOrNull()

    // Отсутствие лимита cgroup v1 выражает абсурдно большим числом — это тоже «нет лимита».
    return v1?.takeIf { it < Long.MAX_VALUE / 2 }
}
