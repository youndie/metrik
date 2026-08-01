package ru.workinprogress.metrik.wire

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistogramTest {
    @Test
    fun `sub-millisecond durations should land in bucket zero`() {
        // Given / When / Then
        assertEquals(0, durationToBucket(0))
        assertEquals(1, durationToBucket(1))
    }

    @Test
    fun `durations above the cap should land in the overflow bucket`() {
        // Given / When / Then
        assertEquals(HISTOGRAM_OVERFLOW_BUCKET, durationToBucket(HISTOGRAM_MAX_MS + 1))
        assertTrue(durationToBucket(HISTOGRAM_MAX_MS) < HISTOGRAM_OVERFLOW_BUCKET)
    }

    @Test
    fun `bucket index should never decrease as duration grows`() {
        // Given
        var previous = 0

        // When / Then
        (0L..HISTOGRAM_MAX_MS).forEach { duration ->
            val bucket = durationToBucket(duration)
            assertTrue(bucket >= previous, "bucket dropped at ${duration}ms: $previous -> $bucket")
            previous = bucket
        }
    }

    @Test
    fun `bucket bounds should contain the duration that produced the bucket`() {
        // Given
        val durations = listOf(1L, 2L, 7L, 50L, 137L, 999L, 4321L, HISTOGRAM_MAX_MS)

        // When / Then
        durations.forEach { duration ->
            val bucket = durationToBucket(duration)
            val lower = bucketLowerBoundMs(bucket)
            val upper = bucketUpperBoundMs(bucket)

            assertTrue(
                duration > lower - 1e-9 && duration <= upper + 1e-9,
                "${duration}ms fell outside bucket $bucket bounds ($lower, $upper]",
            )
        }
    }

    @Test
    fun `percentile should stay within the documented error bound`() {
        // Given — обещание из docs: относительная погрешность перцентиля не больше ширины бакета.
        // Тест держит именно это обещание, а не текущую реализацию.
        val random = Random(42)
        val samples = List(10_000) { random.nextLong(1, 3_000) }
        val histogram = Histogram().also { h -> samples.forEach(h::record) }
        val sorted = samples.sorted()

        // When / Then
        listOf(0.5, 0.9, 0.95, 0.99).forEach { quantile ->
            val rank = ceil(quantile * sorted.size).toInt().coerceIn(1, sorted.size)
            val exact = sorted[rank - 1].toDouble()
            val approximate = histogram.percentileMs(quantile)
            val error = abs(approximate - exact) / exact

            assertTrue(
                error <= 0.2,
                "p${quantile * 100}: exact=$exact approx=$approximate error=${error * 100}%",
            )
        }
    }

    @Test
    fun `percentile should report the cap when it falls into the overflow bucket`() {
        // Given
        val histogram = Histogram.of(5, HISTOGRAM_MAX_MS * 3)

        // When
        val p99 = histogram.percentileMs(0.99)

        // Then
        assertEquals(HISTOGRAM_MAX_MS.toDouble(), p99)
    }

    @Test
    fun `percentile of an empty histogram should be zero`() {
        // Given / When / Then
        assertEquals(0.0, Histogram().percentileMs(0.95))
    }

    @Test
    fun `merging should equal recording both sets into one histogram`() {
        // Given — так сервер складывает окна разных инстансов за одну минуту.
        val first = Histogram.of(1, 5, 5, 900)
        val second = Histogram.of(5, 42, 12_000)
        val combined = Histogram.of(1, 5, 5, 900, 5, 42, 12_000)

        // When
        val merged = first + second

        // Then
        assertEquals(combined, merged)
        assertEquals(7L, merged.totalCount)
    }

    @Test
    fun `sparse form should carry only non-empty buckets in ascending order`() {
        // Given
        val histogram = Histogram.of(2_000, 5, 5)

        // When
        val sparse = histogram.toSparse()

        // Then
        assertEquals(2, sparse.size)
        assertTrue(sparse[0][0] < sparse[1][0])
        assertEquals(2, sparse[0][1])
        assertEquals(1, sparse[1][1])
    }

    @Test
    fun `histogram should survive a round trip through the wire format`() {
        // Given
        val histogram = Histogram.of(1, 17, 350, 9_999, 30_000)

        // When
        val restored = MetrikJson.decodeFromString<Histogram>(MetrikJson.encodeToString(histogram))

        // Then
        assertEquals(histogram, restored)
        assertEquals(histogram.totalCount, restored.totalCount)
    }
}
