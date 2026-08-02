package ru.workinprogress.metrik.server.ingest

import kotlinx.serialization.Serializable
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndIncrement

/**
 * Внутренние счётчики приёма.
 *
 * Не «на будущее»: без них отброшенные и потерянные пакеты невидимы, и странные графики нечем
 * объяснить. Отдаются в `/api/self`.
 */
@OptIn(ExperimentalAtomicApi::class)
class IngestCounters {
    private val accepted = AtomicInt(0)
    private val duplicate = AtomicInt(0)
    private val malformed = AtomicInt(0)
    private val unknownVersion = AtomicInt(0)
    private val badKey = AtomicInt(0)
    private val clockSkew = AtomicInt(0)
    private val failed = AtomicInt(0)
    private val missedWindows = AtomicInt(0)

    fun record(result: IngestResult): IngestResult {
        when (result) {
            IngestResult.ACCEPTED -> accepted
            IngestResult.DUPLICATE -> duplicate
            IngestResult.MALFORMED -> malformed
            IngestResult.UNKNOWN_VERSION -> unknownVersion
            IngestResult.BAD_KEY -> badKey
            IngestResult.CLOCK_SKEW -> clockSkew
        }.fetchAndIncrement()

        return result
    }

    /** Пакет, на котором приём упал с исключением: тоже потеря, тоже должна быть видна. */
    fun recordFailure() {
        failed.fetchAndIncrement()
    }

    /** Дырка в номерах окон инстанса: окна, которые не долетели вовсе. */
    fun recordMissedWindows(count: Int) {
        repeat(count.coerceAtLeast(0)) { missedWindows.fetchAndIncrement() }
    }

    fun snapshot(): IngestStats =
        IngestStats(
            accepted = accepted.load(),
            duplicate = duplicate.load(),
            malformed = malformed.load(),
            unknownVersion = unknownVersion.load(),
            badKey = badKey.load(),
            clockSkew = clockSkew.load(),
            failed = failed.load(),
            missedWindows = missedWindows.load(),
        )
}

/** Счётчики агента внутри этого же процесса — только при включённом самонаблюдении. */
@Serializable
data class AgentStats(
    val loops: Int,
    val exited: Int,
    val windows: Int,
    val dropped: Int,
    val sendFailures: Int,
    val oversized: Int,
)

@Serializable
data class IngestStats(
    val accepted: Int,
    val duplicate: Int,
    val malformed: Int,
    val unknownVersion: Int,
    val badKey: Int,
    val clockSkew: Int,
    val failed: Int,
    val missedWindows: Int,
    val agent: AgentStats? = null,
)
