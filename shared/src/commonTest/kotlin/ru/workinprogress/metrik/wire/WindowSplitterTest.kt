package ru.workinprogress.metrik.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowSplitterTest {
    private val header =
        WindowHeader(
            apiKey = "mk_live_test",
            service = "orders-api",
            instance = "orders-api-7d9f8-x2k1",
            windowStart = 1_754_049_600_000L,
            windowSeq = 1L,
            release = "1.4.212",
        )

    private val system =
        SystemSnapshot(
            heapUsedBytes = 268_435_456,
            heapMaxBytes = 1_073_741_824,
            cpuPermille = 340,
            threads = 42,
            uptimeSeconds = 864_000,
        )

    private fun series(index: Int) =
        RouteSeries(
            method = "GET",
            route = "/orders/{orderId}/items/$index/{itemId}",
            status = if (index % 7 == 0) encodeStatus(503) else encodeStatus(200),
            count = 100 + index,
            sumMs = 1_000L + index,
            maxMs = 300 + index,
            buckets = Histogram.of(3, 7, 7, 40, 300L + index),
        )

    private fun parse(packets: List<String>) = packets.map { MetrikJson.decodeFromString<Frame>(it) }

    @Test
    fun `a small window should fit into a single packet`() {
        // Given
        val routes = listOf(series(1))

        // When
        val split = splitWindow(header, routes, system)

        // Then
        assertEquals(1, split.packets.size)
        assertEquals(0, split.oversized)

        val frame = parse(split.packets).single()
        assertEquals(0, frame.packetIndex)
        assertEquals(1, frame.packetCount)
        assertEquals(routes, frame.routes)
        assertNotNull(frame.system)
    }

    @Test
    fun `a large window should split into packets that all fit the mtu budget`() {
        // Given — 300 серий это заметно больше лимита кардинальности агента (200).
        val routes = (1..300).map(::series)

        // When
        val split = splitWindow(header, routes, system)
        val frames = parse(split.packets)

        // Then
        assertTrue(split.packets.size > 1, "expected several packets, got ${split.packets.size}")
        assertEquals(0, split.oversized)

        split.packets.forEach { packet ->
            val size = packet.encodeToByteArray().size
            assertTrue(size <= MAX_PACKET_BYTES, "packet of $size bytes exceeds $MAX_PACKET_BYTES")
        }

        // Ни одна серия не потерялась и не задвоилась.
        assertEquals(routes, frames.flatMap { it.routes })
    }

    @Test
    fun `packet numbering should be consistent across the window`() {
        // Given
        val routes = (1..300).map(::series)

        // When
        val frames = parse(splitWindow(header, routes, system).packets)

        // Then
        frames.forEachIndexed { index, frame ->
            assertEquals(index, frame.packetIndex)
            assertEquals(frames.size, frame.packetCount)
            assertEquals(header.windowStart, frame.windowStart)
            assertEquals(header.windowSeq, frame.windowSeq)
        }
    }

    @Test
    fun `the system snapshot should travel only in the first packet`() {
        // Given
        val routes = (1..300).map(::series)

        // When
        val frames = parse(splitWindow(header, routes, system).packets)

        // Then
        assertNotNull(frames.first().system)
        frames.drop(1).forEach { assertNull(it.system) }
    }

    @Test
    fun `slow samples should travel only in the last packet`() {
        // Given
        val routes = (1..300).map(::series)
        val slow = (1..5).map { SlowSample("POST", "/orders", encodeStatus(503), 8_000 + it, 1L * it) }

        // When
        val frames = parse(splitWindow(header, routes, system, slow).packets)

        // Then
        assertEquals(slow, frames.last().slow)
        frames.dropLast(1).forEach { assertNull(it.slow) }
    }

    @Test
    fun `the release tag should travel only in the first packet`() {
        // Given
        val routes = (1..300).map(::series)

        // When
        val frames = parse(splitWindow(header, routes, system).packets)

        // Then
        assertEquals(header.release, frames.first().release)
        frames.drop(1).forEach { assertNull(it.release) }
    }

    @Test
    fun `an empty window should still produce one packet`() {
        // Given — окно без единого запроса это не молчание сервиса, и сервер должен это различать.
        // When
        val split = splitWindow(header, routes = emptyList(), system = system)

        // Then
        assertEquals(1, split.packets.size)

        val frame = parse(split.packets).single()
        assertTrue(frame.routes.isEmpty())
        assertNotNull(frame.system)
    }

    @Test
    fun `a series too large for one packet should be reported rather than dropped`() {
        // Given — патологически длинный шаблон маршрута.
        val giant = series(1).copy(route = "/" + "x".repeat(MAX_PACKET_BYTES * 2))

        // When
        val split = splitWindow(header, listOf(giant))

        // Then
        assertEquals(1, split.packets.size)
        assertEquals(1, split.oversized)
        assertEquals(giant, parse(split.packets).single().routes.single())
    }
}
