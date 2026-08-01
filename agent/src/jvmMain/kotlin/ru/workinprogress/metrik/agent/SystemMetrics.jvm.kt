package ru.workinprogress.metrik.agent

/**
 * JVM-реализация без `java.management`.
 *
 * Ключевая деталь: CPU-время процесса достаётся из `ProcessHandle` (`java.base`, JDK 9+), а не из
 * `OperatingSystemMXBean` — поэтому базовый набор работает и на рантайме, собранном jlink без
 * модуля `java.management`.
 */
actual fun readSystemMetrics(): SystemReading {
    val runtime = Runtime.getRuntime()
    val used = runtime.totalMemory() - runtime.freeMemory()
    val max = runtime.maxMemory().takeIf { it != Long.MAX_VALUE }

    val cpuMillis =
        runCatching {
            ProcessHandle
                .current()
                .info()
                .totalCpuDuration()
                .orElse(null)
                ?.toMillis()
        }.getOrNull()

    val gc = readGcCounters()

    return SystemReading(
        memoryUsedBytes = used,
        memoryLimitBytes = max,
        cpuMillis = cpuMillis,
        threads = Thread.activeCount(),
        gcCollections = gc?.first,
        gcTotalMs = gc?.second,
    )
}

actual fun availableProcessors(): Int = Runtime.getRuntime().availableProcessors()

/**
 * GC-счётчики требуют `java.management`, которого может не быть в урезанном рантайме.
 * Доступность проверяется один раз и мягко: нет модуля — просто нет поля `gc` в пакете.
 * Тот же приём использует сам Ktor в `MicrometerMetrics` (`isManagementFactoryAvailable`).
 */
private val managementAvailable: Boolean by lazy {
    runCatching { Class.forName("java.lang.management.ManagementFactory") }.isSuccess
}

private fun readGcCounters(): Pair<Int, Long>? {
    if (!managementAvailable) return null

    return runCatching {
        var collections = 0L
        var millis = 0L

        java.lang.management.ManagementFactory.getGarbageCollectorMXBeans().forEach { bean ->
            if (bean.collectionCount >= 0) collections += bean.collectionCount
            if (bean.collectionTime >= 0) millis += bean.collectionTime
        }

        collections.toInt() to millis
    }.getOrNull()
}
