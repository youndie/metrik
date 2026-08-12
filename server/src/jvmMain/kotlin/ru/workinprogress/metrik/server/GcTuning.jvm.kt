package ru.workinprogress.metrik.server

/** На JVM сборщик настраивается флагами запуска. */
actual fun tuneGc(
    targetHeapBytes: Long?,
    targetUtilization: Double?,
): String? = null
