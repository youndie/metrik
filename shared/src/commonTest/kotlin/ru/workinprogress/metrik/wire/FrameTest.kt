package ru.workinprogress.metrik.wire

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class FrameTest {
    private val frame =
        Frame(
            apiKey = "mk_live_test",
            service = "orders-api",
            instance = "orders-api-7d9f8-x2k1",
            release = "1.4.212",
            windowStart = 1_754_049_600_000L,
            windowSeq = 12_043L,
            packetIndex = 0,
            packetCount = 1,
            routes =
                listOf(
                    RouteSeries(
                        method = "GET",
                        route = "/orders/{id}",
                        status = encodeStatus(200),
                        count = 1_043,
                        sumMs = 18_234,
                        maxMs = 812,
                        buckets = Histogram.of(3, 7, 7, 40, 812),
                    ),
                ),
            system =
                SystemSnapshot(
                    heapUsedBytes = 268_435_456,
                    heapMaxBytes = 1_073_741_824,
                    cpuPermille = 340,
                    threads = 42,
                    uptimeSeconds = 864_000,
                    gc = GcSnapshot(collections = 12, totalMs = 87),
                ),
            slow = listOf(SlowSample("POST", "/orders", encodeStatus(503), 8_123, 1_754_049_612_345L)),
        )

    @Test
    fun `frame should survive a round trip`() {
        // Given / When
        val restored = MetrikJson.decodeFromString<Frame>(MetrikJson.encodeToString(frame))

        // Then
        assertEquals(frame, restored)
    }

    @Test
    fun `wire keys should stay short`() {
        // Given / When — длина ключей это количество серий, которое влезет в 1200 байт.
        val json = MetrikJson.encodeToString(frame)

        // Then
        listOf("\"v\":", "\"k\":", "\"s\":", "\"i\":", "\"t\":", "\"w\":", "\"q\":", "\"n\":", "\"r\":")
            .forEach { key -> assertContains(json, key) }
        assertFalse(json.contains("apiKey"), "long field names leaked onto the wire: $json")
    }

    @Test
    fun `absent optional fields should not be encoded`() {
        // Given — отсутствие gc это нормальная ситуация, а не ошибка; платить за неё байтами незачем.
        val minimal =
            frame.copy(
                release = null,
                system = frame.system?.copy(heapMaxBytes = null, gc = null),
                slow = null,
            )

        // When
        val json = MetrikJson.encodeToString(minimal)

        // Then
        assertFalse(json.contains("\"rel\""), json)
        assertFalse(json.contains("\"gc\""), json)
        assertFalse(json.contains("\"hm\""), json)
        assertFalse(json.contains("\"x\":["), json)
        assertNull(MetrikJson.decodeFromString<Frame>(json).system?.gc)
    }

    @Test
    fun `unknown keys should be ignored`() {
        // Given — контракт совместимости: аддитивные поля агент шлёт, не поднимая версию протокола.
        val json = MetrikJson.encodeToString(frame).dropLast(1) + ""","zz":{"future":true}}"""

        // When
        val restored = MetrikJson.decodeFromString<Frame>(json)

        // Then
        assertEquals(frame, restored)
    }

    @Test
    fun `protocol version should be written explicitly`() {
        // Given / When — сервер обязан увидеть версию, даже если она равна значению по умолчанию.
        val json = MetrikJson.encodeToString(frame)

        // Then
        assertContains(json, "\"v\":$PROTOCOL_VERSION")
    }
}
