package ru.workinprogress.metrik.agent

import ru.workinprogress.metrik.wire.SystemSnapshot

/**
 * Сырое состояние процесса, снятое платформой.
 *
 * [cpuMillis] кумулятивно с момента старта процесса — дельту за окно считает вызывающий.
 * Отсутствующее значение это `null`, а не ноль: «нет данных» и «ноль» это разные вещи, и путать их
 * в мониторинге нельзя.
 */
class SystemReading(
    val memoryUsedBytes: Long?,
    val memoryLimitBytes: Long?,
    val cpuMillis: Long?,
    val threads: Int?,
    val gcCollections: Int? = null,
    val gcTotalMs: Long? = null,
)

/**
 * Снимает состояние процесса **без JMX**: на JVM хватает `java.base` (`Runtime`, `ProcessHandle`),
 * на нативе — `/proc` или `getrusage`. Расширенные счётчики (GC) необязательны и приезжают только
 * там, где источник доступен.
 */
expect fun readSystemMetrics(): SystemReading

/** Число доступных процессу ядер — знаменатель для CPU в промилле. */
expect fun availableProcessors(): Int

/**
 * Считает срез за окно: CPU переводится из кумулятивных миллисекунд в промилле одного ядра,
 * GC-счётчики — в дельту за окно.
 */
class SystemSampler {
    private var previousCpuMillis: Long? = null
    private var previousGcCollections: Int? = null
    private var previousGcMillis: Long? = null

    fun sample(
        windowMs: Long,
        uptimeSeconds: Long,
    ): SystemSnapshot? {
        val reading = runCatching { readSystemMetrics() }.getOrNull() ?: return null
        val memoryUsed = reading.memoryUsedBytes ?: return null

        val cpuPermille = cpuPermille(reading.cpuMillis, windowMs)
        val gc = gcDelta(reading)

        return SystemSnapshot(
            heapUsedBytes = memoryUsed,
            heapMaxBytes = reading.memoryLimitBytes,
            cpuPermille = cpuPermille,
            threads = reading.threads ?: 0,
            uptimeSeconds = uptimeSeconds,
            gc = gc,
        )
    }

    private fun cpuPermille(
        cpuMillis: Long?,
        windowMs: Long,
    ): Int {
        val current = cpuMillis ?: return 0
        val previous = previousCpuMillis
        previousCpuMillis = current

        if (previous == null || windowMs <= 0) return 0

        val delta = (current - previous).coerceAtLeast(0)
        return ((delta * 1000) / windowMs).toInt()
    }

    private fun gcDelta(reading: SystemReading): ru.workinprogress.metrik.wire.GcSnapshot? {
        val collections = reading.gcCollections ?: return null
        val millis = reading.gcTotalMs ?: return null

        val previousCollections = previousGcCollections
        val previousMillis = previousGcMillis

        previousGcCollections = collections
        previousGcMillis = millis

        if (previousCollections == null || previousMillis == null) return null

        return ru.workinprogress.metrik.wire.GcSnapshot(
            collections = (collections - previousCollections).coerceAtLeast(0),
            totalMs = (millis - previousMillis).coerceAtLeast(0),
        )
    }
}
