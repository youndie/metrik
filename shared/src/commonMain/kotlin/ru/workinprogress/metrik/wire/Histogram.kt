package ru.workinprogress.metrik.wire

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.pow

// Гистограмма длительностей: экспоненциальные бакеты, разреженное представление.
// Обоснование выбора γ и границ погрешности — docs/research/research-architecture.md §Р2,
// формат на проводе — docs/api/protocol-ingest.md, «Бакеты гистограммы».

/** Основание шкалы. Ширина бакета 20 % ⇒ погрешность перцентиля ≤ 20 %. */
const val HISTOGRAM_GAMMA: Double = 1.2

/** Всё, что дольше, попадает в overflow-бакет. */
const val HISTOGRAM_MAX_MS: Long = 10_000L

/** Бакет для длительностей больше [HISTOGRAM_MAX_MS]. */
const val HISTOGRAM_OVERFLOW_BUCKET: Int = 52

private val LN_GAMMA = ln(HISTOGRAM_GAMMA)

/**
 * Индекс бакета для длительности в миллисекундах.
 *
 * `0` — быстрее миллисекунды, `1…51` — рабочий диапазон, [HISTOGRAM_OVERFLOW_BUCKET] — переполнение.
 */
fun durationToBucket(durationMs: Long): Int =
    when {
        durationMs < 1L -> 0
        durationMs > HISTOGRAM_MAX_MS -> HISTOGRAM_OVERFLOW_BUCKET
        else -> ceil(ln(durationMs.toDouble()) / LN_GAMMA).toInt().coerceAtLeast(1)
    }

/** Нижняя граница бакета, мс (включительно для бакета 0, исключительно для остальных). */
fun bucketLowerBoundMs(bucket: Int): Double =
    when {
        bucket <= 0 -> 0.0
        bucket >= HISTOGRAM_OVERFLOW_BUCKET -> HISTOGRAM_MAX_MS.toDouble()
        else -> HISTOGRAM_GAMMA.pow(bucket - 1)
    }

/** Верхняя граница бакета, мс (включительно). Для overflow-бакета — бесконечность. */
fun bucketUpperBoundMs(bucket: Int): Double =
    when {
        bucket <= 0 -> 1.0
        bucket >= HISTOGRAM_OVERFLOW_BUCKET -> Double.POSITIVE_INFINITY
        else -> HISTOGRAM_GAMMA.pow(bucket)
    }

/**
 * Разреженная гистограмма: хранятся только непустые бакеты.
 *
 * Общий код агента и сервера — расходиться реализациям нельзя, иначе цифры продукта разъедутся
 * молча. Класс изменяемый: агент инкрементирует его на горячем пути, где аллокации нежелательны.
 */
@Serializable(with = HistogramSerializer::class)
class Histogram {
    private val counts = HashMap<Int, Int>()

    /** Сумма счётчиков всех бакетов. */
    val totalCount: Long
        get() = counts.values.fold(0L) { acc, value -> acc + value }

    /** Учесть одну длительность. */
    fun record(durationMs: Long) {
        add(durationToBucket(durationMs), 1)
    }

    /** Прибавить готовый счётчик к бакету. */
    fun add(
        bucket: Int,
        count: Int,
    ) {
        if (count == 0) return
        require(count > 0) { "count must be positive, got $count" }
        require(bucket in 0..HISTOGRAM_OVERFLOW_BUCKET) { "bucket out of range: $bucket" }
        counts[bucket] = (counts[bucket] ?: 0) + count
    }

    fun countAt(bucket: Int): Int = counts[bucket] ?: 0

    /**
     * Сложить с другой гистограммой побакетно.
     *
     * Именно так сервер объединяет окна разных инстансов: складывать перцентили нельзя,
     * складываются только бакеты.
     */
    fun merge(other: Histogram) {
        other.counts.forEach { (bucket, count) -> add(bucket, count) }
    }

    operator fun plus(other: Histogram): Histogram =
        Histogram().also {
            it.merge(this)
            it.merge(other)
        }

    /**
     * Перцентиль в миллисекундах, с линейной интерполяцией внутри бакета.
     *
     * Значение приблизительное: истинное лежит в том же бакете, то есть отличается не более чем на
     * ширину бакета (20 %). Для трендов и алертов этого достаточно, для SLA-репортинга — нет.
     * Если перцентиль попал в overflow-бакет, возвращается его нижняя граница ([HISTOGRAM_MAX_MS]):
     * «не быстрее 10 секунд», точнее сказать нечего.
     */
    fun percentileMs(quantile: Double): Double {
        require(quantile in 0.0..1.0) { "quantile must be in 0..1, got $quantile" }

        val total = totalCount
        if (total == 0L) return 0.0

        val rank = quantile * total
        var cumulative = 0L

        for (bucket in counts.keys.sorted()) {
            val count = counts.getValue(bucket)
            val next = cumulative + count

            if (next >= rank) {
                if (bucket >= HISTOGRAM_OVERFLOW_BUCKET) return HISTOGRAM_MAX_MS.toDouble()

                val lower = bucketLowerBoundMs(bucket)
                val upper = bucketUpperBoundMs(bucket)
                val within = ((rank - cumulative) / count).coerceIn(0.0, 1.0)
                return lower + (upper - lower) * within
            }

            cumulative = next
        }

        return bucketUpperBoundMs(counts.keys.max())
    }

    /** Представление для провода: пары `[индекс, счётчик]`, отсортированные по индексу. */
    fun toSparse(): List<List<Int>> = counts.keys.sorted().map { listOf(it, counts.getValue(it)) }

    override fun equals(other: Any?): Boolean = other is Histogram && other.counts == counts

    override fun hashCode(): Int = counts.hashCode()

    override fun toString(): String = "Histogram(${toSparse()})"

    companion object {
        fun of(vararg durationsMs: Long): Histogram = Histogram().also { h -> durationsMs.forEach(h::record) }

        fun fromSparse(sparse: List<List<Int>>): Histogram =
            Histogram().also { histogram ->
                sparse.forEach { pair ->
                    require(pair.size == 2) { "expected [bucket, count], got $pair" }
                    histogram.add(pair[0], pair[1])
                }
            }
    }
}

object HistogramSerializer : KSerializer<Histogram> {
    private val delegate = ListSerializer(ListSerializer(Int.serializer()))

    override val descriptor = delegate.descriptor

    override fun serialize(
        encoder: Encoder,
        value: Histogram,
    ) = delegate.serialize(encoder, value.toSparse())

    override fun deserialize(decoder: Decoder): Histogram = Histogram.fromSparse(delegate.deserialize(decoder))
}
