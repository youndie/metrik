package ru.workinprogress.metrik.server

import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi

@OptIn(NativeRuntimeApi::class)
actual fun tuneGc(
    targetHeapBytes: Long?,
    targetUtilization: Double?,
    autotune: Boolean?,
): String? {
    if (targetHeapBytes == null && targetUtilization == null && autotune == null) return null

    // Порядок важен: autotune задаётся первым, иначе включённый autotune тут же пересчитает
    // только что выставленный targetHeapBytes и замер измерит не то, что задавали.
    autotune?.let { GC.autotune = it }
    targetHeapBytes?.let { GC.targetHeapBytes = it }
    targetUtilization?.let { GC.targetHeapUtilization = it }

    return "target=${GC.targetHeapBytes / 1024 / 1024}MiB utilization=${GC.targetHeapUtilization} autotune=${GC.autotune}"
}
