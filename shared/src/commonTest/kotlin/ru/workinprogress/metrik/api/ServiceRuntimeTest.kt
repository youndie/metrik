package ru.workinprogress.metrik.api

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Рантайм берётся из поля агента, а не выводится из данных.
 *
 * Тест держит именно тот случай, на котором дашборд врал: нативный процесс в контейнере, у
 * которого `heapMaxBytes` заполнен лимитом cgroup. По этому признаку его считали JVM.
 */
class ServiceRuntimeTest {
    private fun point(
        runtime: String?,
        heapMax: Long?,
    ) = SystemPoint(
        instance = "pod-a",
        at = 1,
        runtime = runtime,
        heapUsedBytes = 105_926_656,
        heapMaxBytes = heapMax,
        cpuPermille = 10,
        threads = 8,
    )

    @Test
    fun `a native instance in a container should stay native despite a memory limit`() {
        // Given — ровно то, что metrik-server присылает о себе в кластере.
        val inContainer = point(runtime = "native", heapMax = 268_435_456)

        // Then
        assertEquals(ServiceRuntime.NATIVE, inContainer.serviceRuntime)
    }

    @Test
    fun `a jvm instance should be jvm`() {
        assertEquals(ServiceRuntime.JVM, point(runtime = "jvm", heapMax = 536_870_912).serviceRuntime)
    }

    @Test
    fun `an old agent should be unknown and not guessed`() {
        // Агент поля не шлёт. Отсутствие heapMaxBytes раньше означало бы «native», наличие — «JVM»;
        // и то и другое было бы выдумкой.
        assertEquals(ServiceRuntime.UNKNOWN, point(runtime = null, heapMax = null).serviceRuntime)
        assertEquals(ServiceRuntime.UNKNOWN, point(runtime = null, heapMax = 268_435_456).serviceRuntime)
    }
}
