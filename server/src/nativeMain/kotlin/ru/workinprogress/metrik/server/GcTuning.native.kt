package ru.workinprogress.metrik.server

import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi

@OptIn(NativeRuntimeApi::class)
actual fun tuneGc(
    targetHeapBytes: Long?,
    targetUtilization: Double?,
): String? {
    if (targetHeapBytes == null && targetUtilization == null) return null

    // autotune остаётся включённым намеренно: с ним targetHeapBytes — это порог, после которого
    // сборка назначается агрессивнее, а не стена. Выключенный autotune вместе с низким порогом и
    // даёт непрерывные сборки, о которых предупреждает документация.
    GC.autotune = true
    targetHeapBytes?.let { GC.targetHeapBytes = it }
    targetUtilization?.let { GC.targetHeapUtilization = it }

    return "target=${GC.targetHeapBytes / 1024 / 1024}MiB utilization=${GC.targetHeapUtilization} autotune=${GC.autotune}"
}
