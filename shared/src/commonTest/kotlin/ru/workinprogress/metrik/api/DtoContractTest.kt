package ru.workinprogress.metrik.api

import ru.workinprogress.metrik.wire.MetrikJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Контракт сериализации между сервером и дашбордом.
 *
 * Сервер кодирует с `explicitNulls = false`, то есть null-поле из ответа **исчезает**. Поле без
 * значения по умолчанию на приёме тогда падает с «Field is required», и ломается разбор всего
 * ответа, а не одной ячейки: дашборд показывает «нет связи с сервером» вместо списка сервисов.
 *
 * Ровно это и случилось: у сервиса, который ещё ни разу не присылал окон, `lastSeenAt` равен null,
 * и весь `/api/services` переставал разбираться.
 */
class DtoContractTest {
    @Test
    fun `a summary without lastSeenAt should survive the round trip`() {
        // Given — сервис, которого ещё ни разу не видели.
        val summary =
            ServiceSummary(
                id = 1,
                name = "shildik",
                requestsPerSecond = 0.0,
                errorRate = 0.0,
                p95Ms = 0.0,
                lastSeenAt = null,
                instances = 0,
                clockSkew = false,
            )

        // When
        val json = MetrikJson.encodeToString(summary)

        // Then — поля в JSON действительно нет, и оно всё равно разбирается.
        assertFalse(json.contains("lastSeenAt"), "explicitNulls=false обязан выкинуть поле: $json")
        assertEquals(summary, MetrikJson.decodeFromString<ServiceSummary>(json))
    }

    /**
     * У нативного процесса нет ни максимума heap, ни счётчиков GC — то есть у любого
     * Kotlin/Native-сервиса эти поля null всегда, и вкладка «Система» ломалась именно на них.
     */
    @Test
    fun `a native system point should survive the round trip without heap and gc`() {
        // Given
        val point =
            SystemPoint(
                instance = "pod-a",
                at = 1_754_049_600_000,
                heapUsedBytes = 42_000_000,
                heapMaxBytes = null,
                cpuPermille = 15,
                threads = 8,
                gcCollections = null,
                gcMs = null,
            )

        // When
        val decoded = MetrikJson.decodeFromString<SystemPoint>(MetrikJson.encodeToString(point))

        // Then
        assertEquals(point, decoded)
        assertNull(decoded.heapMaxBytes)
        assertNull(decoded.gcMs)
    }

    /**
     * Дашборд читает ответы тем же `MetrikJson`. Тест держит инвариант «любое nullable-поле имеет
     * дефолт» для всего контракта сразу: добавили поле без дефолта — тест падает здесь, а не в
     * проде на сервисе, у которого это поле пустое.
     */
    @Test
    fun `every nullable field in the contract should have a default`() {
        // Given — минимально заполненные объекты: всё необязательное опущено.
        val minimal =
            listOf(
                MetrikJson.encodeToString(AlertView(service = "s", ruleId = "absent", state = "FIRING", since = 1)),
                MetrikJson.encodeToString(
                    AlertRuleView(ruleId = "absent", threshold = 1.0, minCount = 0, windows = 5, enabled = true),
                ),
            )

        // When / Then — разбор не должен требовать ни одного из опущенных полей.
        assertEquals("FIRING", MetrikJson.decodeFromString<AlertView>(minimal[0]).state)
        assertNull(MetrikJson.decodeFromString<AlertRuleView>(minimal[1]).mutedUntil)
    }
}
