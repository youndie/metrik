package ru.workinprogress.metrik.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import ru.workinprogress.metrik.wire.WindowHeader
import ru.workinprogress.metrik.wire.splitWindow
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndIncrement
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Размер входящей очереди замеров.
 *
 * Очередь **ограничена** намеренно: неограниченный канал под нагрузкой превращается в утечку
 * памяти в чужом процессе. Переполнение — это потеря замеров, но потеря видимая (счётчик
 * [AgentCounters.dropped]), а не съеденная память целевого сервиса.
 */
private const val INBOX_CAPACITY = 16_384

private class Sample(
    val method: String,
    val route: String,
    val status: Int,
    val durationMs: Long,
    val timestampMs: Long,
)

/** Счётчики самого агента: без них потери невидимы. */
@OptIn(ExperimentalAtomicApi::class)
class AgentCounters {
    internal val droppedCounter = AtomicInt(0)
    internal val sendFailureCounter = AtomicInt(0)
    internal val oversizedCounter = AtomicInt(0)

    internal val windowCounter = AtomicInt(0)

    /** Замеры, не влезшие в очередь. */
    val dropped: Int get() = droppedCounter.load()

    /** Сколько окон агент закрыл и попытался отправить. Ноль здесь означает, что цикл не крутится. */
    val windows: Int get() = windowCounter.load()

    /** Окна, которые не удалось отправить. */
    val sendFailures: Int get() = sendFailureCounter.load()

    /** Пакеты, превысившие MTU-бюджет (аномально длинный шаблон маршрута). */
    val oversized: Int get() = oversizedCounter.load()
}

/**
 * Рантайм агента: приём замеров с горячего пути, агрегация окна, отправка.
 *
 * Горячий путь ([record]) не блокируется, не аллоцирует ничего тяжелее одного объекта замера и
 * никогда не бросает. Всё остальное происходит в единственной корутине-потребителе, поэтому
 * агрегатор не нуждается ни в локах, ни в атомиках.
 */
@OptIn(ExperimentalTime::class, ExperimentalAtomicApi::class)
class MetrikAgent(
    private val config: MetrikConfig,
    private val sender: MetrikSender,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val inbox = Channel<Sample>(capacity = INBOX_CAPACITY, onBufferOverflow = BufferOverflow.DROP_LATEST)
    private val aggregator = WindowAggregator(config.maxSeries, config.slowSamples)
    private val sampler = SystemSampler()
    private val startedAtMs = nowMs()

    private var job: Job? = null
    private var windowSeq = 0L

    val counters = AgentCounters()

    fun start(scope: CoroutineScope) {
        job = scope.launch { run() }
    }

    fun stop() {
        job?.cancel()
        job = null
        sender.close()
    }

    /** Вызывается с горячего пути. Никогда не suspend, никогда не бросает. */
    fun record(
        method: String,
        route: String,
        status: Int,
        durationMs: Long,
    ) {
        val sample = Sample(method, route, status, durationMs, nowMs())
        if (inbox.trySend(sample).isFailure) counters.droppedCounter.fetchAndIncrement()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun run() {
        var windowStart = alignToWindow(nowMs())

        while (currentScopeIsActive()) {
            val deadline = windowStart + config.windowMs
            val remaining = deadline - nowMs()

            if (remaining <= 0) {
                flush(windowStart)
                windowStart = deadline
                continue
            }

            // select, а не withTimeoutOrNull { receive() }: последний способен потерять уже
            // полученный элемент, если таймаут выстрелит между получением и возвратом.
            val sample =
                select<Sample?> {
                    inbox.onReceive { it }
                    onTimeout(remaining) { null }
                }

            if (sample == null) {
                flush(windowStart)
                windowStart = deadline
            } else {
                aggregator.record(sample.method, sample.route, sample.status, sample.durationMs, sample.timestampMs)
            }
        }
    }

    private suspend fun currentScopeIsActive(): Boolean = kotlin.coroutines.coroutineContext.isActive

    private suspend fun flush(windowStart: Long) {
        counters.windowCounter.fetchAndIncrement()

        val data = aggregator.drain()
        val system =
            if (config.systemMetrics) {
                sampler.sample(config.windowMs, (nowMs() - startedAtMs) / 1000)
            } else {
                null
            }

        // Пустое окно всё равно отправляется: «запросов не было» и «сервис молчит» — разные вещи,
        // и различить их сервер может только по приходящим окнам.
        val split =
            splitWindow(
                header =
                    WindowHeader(
                        apiKey = config.apiKey,
                        service = config.service,
                        instance = config.instanceId,
                        windowStart = windowStart,
                        windowSeq = windowSeq++,
                        release = config.release,
                        windowMs = config.windowMs,
                    ),
                routes = data.routes,
                system = system,
                slow = data.slow,
            )

        if (split.oversized > 0) counters.oversizedCounter.fetchAndIncrement()

        split.packets.forEach { packet ->
            try {
                sender.send(packet)
            } catch (cause: Throwable) {
                if (cause is kotlinx.coroutines.CancellationException) throw cause
                counters.sendFailureCounter.fetchAndIncrement()
            }
        }
    }

    private fun alignToWindow(timestampMs: Long): Long = timestampMs - timestampMs % config.windowMs
}
