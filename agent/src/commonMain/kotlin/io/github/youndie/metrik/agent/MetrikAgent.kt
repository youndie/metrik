package io.github.youndie.metrik.agent

import io.github.youndie.metrik.wire.WindowHeader
import io.github.youndie.metrik.wire.splitWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndIncrement
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime

/**
 * Размер входящей очереди замеров.
 *
 * Очередь **ограничена** намеренно: неограниченный канал под нагрузкой превращается в утечку
 * памяти в чужом процессе. Переполнение — это потеря замеров, но потеря видимая (счётчик
 * [AgentCounters.dropped]), а не съеденная память целевого сервиса.
 *
 * `internal`, а не `private`: на этой величине держится тест переполнения, и повторять её в тесте
 * числом значило бы проверять совпадение двух констант, а не поведение очереди.
 */
internal const val INBOX_CAPACITY = 16_384

/** Шаг опроса очереди. Окно минутное, так что точность здесь роли не играет. */
private const val POLL_INTERVAL_MS = 200L

private class Sample(
    val method: String,
    val route: String,
    val status: Int,
    val durationMs: Long,
    val timestampMs: Long,
)

/** Счётчики самого агента: без них потери невидимы. */
@OptIn(ExperimentalAtomicApi::class)
public class AgentCounters {
    internal val droppedCounter = AtomicInt(0)
    internal val sendFailureCounter = AtomicInt(0)
    internal val oversizedCounter = AtomicInt(0)

    internal val windowCounter = AtomicInt(0)
    internal val loopCounter = AtomicInt(0)
    internal val exitCounter = AtomicInt(0)

    /** Замеры, не влезшие в очередь. */
    public val dropped: Int get() = droppedCounter.load()

    /** Сколько окон агент закрыл и попытался отправить. */
    public val windows: Int get() = windowCounter.load()

    /**
     * Итерации цикла окон. Ноль означает, что корутина агента вообще не получила выполнения;
     * ненулевое значение при нулевых [windows] — что не срабатывает таймер окна.
     * Без этого различия «данных нет» диагностике не поддаётся.
     */
    public val loops: Int get() = loopCounter.load()

    /** Вышел ли цикл окон. Отличает «корутину отменили» от «залипли в ожидании». */
    public val exited: Int get() = exitCounter.load()

    /** Окна, которые не удалось отправить. */
    public val sendFailures: Int get() = sendFailureCounter.load()

    /** Пакеты, превысившие MTU-бюджет (аномально длинный шаблон маршрута). */
    public val oversized: Int get() = oversizedCounter.load()
}

/**
 * Рантайм агента: приём замеров с горячего пути, агрегация окна, отправка.
 *
 * Горячий путь ([record]) не блокируется, не аллоцирует ничего тяжелее одного объекта замера и
 * никогда не бросает. Всё остальное происходит в единственной корутине-потребителе, поэтому
 * агрегатор не нуждается ни в локах, ни в атомиках.
 */
@OptIn(ExperimentalAtomicApi::class)
public class MetrikAgent(
    private val config: MetrikConfig,
    private val sender: MetrikSender,
    @Suppress(
        "ktlint:kapkan:wall-clock",
        "это и есть порт часов: время входит здесь одним значением по умолчанию, а тесты его подменяют",
    )
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    /**
     * Очередь **без** `onBufferOverflow`: политика переполнения здесь — отказ `trySend`, и только
     * он доходит до счётчика.
     *
     * Раньше стояло `BufferOverflow.DROP_LATEST`, и это молча отменяло всю видимость потерь:
     * канал с политикой переполнения не отказывает — `trySend` возвращает успех и выбрасывает
     * замер сам. Ветка `isFailure` в [record] не исполнялась никогда, [AgentCounters.dropped]
     * оставался нулём при любой потере, а документация обещала обратное. Поведение очереди от
     * правки не меняется — переполнение по-прежнему теряет самый свежий замер, — меняется
     * только то, что теперь оно посчитано.
     */
    private val inbox = Channel<Sample>(capacity = INBOX_CAPACITY)
    private val aggregator = WindowAggregator(config.maxSeries, config.slowSamples)
    private val sampler = SystemSampler()
    private val startedAtMs = nowMs()

    private var job: Job? = null
    private var windowSeq = 0L

    public val counters: AgentCounters = AgentCounters()

    /**
     * Собственный поток, а не `Dispatchers.Default`.
     *
     * Цикл окон держится на `delay`, а `delay` на общем пуле возобновляет тот же пул. В
     * Kotlin/Native каждый `SelectorManager` занимает воркер `Dispatchers.Default` намертво своим
     * `select()`: на двухъядерной ноде хосту достаточно поднять два Ktor-движка, чтобы свободных
     * воркеров не осталось и таймеры перестали срабатывать во всём процессе — агент замолкает
     * целиком, а мониторинг показывает «сервис молчит» у совершенно живого сервиса.
     *
     * Своим потоком агент перестаёт зависеть от того, что хост делает с общим пулом. Это то же
     * лекарство, что уже применено к сокету (см. [UdpMetrikSender]), и та же причина: агент обязан
     * не влиять на сервис, в который встроен, — и не ломаться от того, как устроен этот сервис.
     */
    @OptIn(DelicateCoroutinesApi::class)
    private val dispatcher = newSingleThreadContext("metrik-agent")

    // Собственный scope, а не хостовый: агент не должен умирать оттого, что чужой Job отменили.
    // Останавливается явно в stop().
    private val scope = CoroutineScope(dispatcher + SupervisorJob())

    /**
     * Начало окна, которое сейчас набирается.
     *
     * Поле, а не только локальная переменная цикла, потому что [stopAndFlush] зовут снаружи — с
     * чужого потока, когда цикл уже отменён, — и ему нужно знать, каким окном подписать то, что
     * осталось. `@Volatile` здесь ровно за этим: запись делает поток агента, читает чужой.
     */
    @Volatile
    private var currentWindowStart: Long = 0L

    /**
     * Поднимает цикл окон — **в собственном scope агента**, без хостового.
     *
     * Раньше функция принимала `host: CoroutineScope` и не использовала его ни в одной строке.
     * Подпись при этом повторяла настоящую идиому репозитория (`AlertWorker.start(scope)`,
     * `RetentionWorker.start(scope)`, `UdpReceiver.start(scope)` — те действительно делают
     * `scope.launch`), поэтому читалась как правда: вызывающий видел знакомую форму и заключал,
     * что временем жизни агента распоряжается он. Распоряжаться им нельзя было никогда — и это
     * намеренно, см. [scope]: агент не должен умирать оттого, что чужой Job отменили, иначе
     * мониторинг гаснет ровно в ту минуту, когда в хосте что-то пошло не так.
     *
     * Остановка — по-прежнему явная: [stopAndFlush] (с досылкой окна) или [stop].
     */
    public fun start() {
        job = scope.launch { run() }
    }

    /**
     * Оставлено на один выпуск ради тех, кто уже написал `start(scope)`.
     *
     * Аргумент отбрасывается — как отбрасывался и до этого.
     */
    @Deprecated(
        "агент крутится в собственном scope; хостовый аргумент не использовался никогда",
        ReplaceWith("start()"),
    )
    public fun start(host: CoroutineScope) {
        start()
    }

    /**
     * Останавливает агента, **не** отправив открытое окно.
     *
     * Оставлен для вызывающего, которому нечего ждать: тест, локальный прогон, аварийное
     * выключение. Всё, что останавливается по-человечески, зовёт [stopAndFlush] — иначе каждое
     * выключение стоит до одного окна агрегации, а это до минуты на умолчаниях.
     */
    public fun stop() {
        job?.cancel()
        job = null
        scope.cancel()
        sender.close()
        dispatcher.close()
    }

    /**
     * Досылает открытое окно и только потом останавливается — issue #29.
     *
     * `stop()` отменял цикл, scope, sender и dispatcher и **не сбрасывал то, что уже насчитано**:
     * агрегатор держит текущее окно в памяти и отдаёт его по таймеру, так что выключение между
     * двумя тиками теряло всё, что случилось после последнего. При окне по умолчанию это до минуты
     * метрик на каждой остановке — и ровно той минуты, в которую сервис останавливали.
     *
     * Порядок здесь — тоже часть починки: цикл отменяется первым, чтобы он не отправил то же окно
     * параллельно, потом разбирается входящая очередь (иначе последние записи остались бы в
     * канале), потом отправка под таймаутом, и только после — закрытие отправителя. Отправитель,
     * закрытый раньше отправки, превратил бы починку в тихий no-op.
     *
     * @param grace сколько ждать саму отправку. Не бюджет всей остановки: вызывающий знает свой.
     */
    public suspend fun stopAndFlush(grace: Duration = config.windowMs.milliseconds) {
        job?.cancel()
        job = null

        withTimeoutOrNull(grace) {
            drainInbox()
            flush(currentWindowStart)
        }

        scope.cancel()
        sender.close()
        dispatcher.close()
    }

    /** Вызывается с горячего пути. Никогда не suspend, никогда не бросает. */
    public fun record(
        method: String,
        route: String,
        status: Int,
        durationMs: Long,
    ) {
        val sample = Sample(method, route, status, durationMs, nowMs())
        if (inbox.trySend(sample).isFailure) counters.droppedCounter.fetchAndIncrement()
    }

    /**
     * Цикл окна: разобрать накопившиеся замеры и закрыть окно по времени.
     *
     * Намеренно **без `select { onTimeout }`**: на linuxX64 эта конструкция один раз вошла в
     * ожидание и больше не просыпалась (на macOS и под эмуляцией в docker тот же бинарь работал —
     * похоже на гонку, которую маскирует эмуляция). Здесь только `delay` и неблокирующий
     * `tryReceive`: короткий шаг опроса стоит несколько пробуждений в секунду и не зависит
     * от поведения таймера внутри select.
     */
    private suspend fun run() {
        var windowStart = alignToWindow(nowMs())
        currentWindowStart = windowStart

        try {
            while (currentScopeIsActive()) {
                counters.loopCounter.fetchAndIncrement()

                val deadline = windowStart + config.windowMs
                val remaining = deadline - nowMs()

                if (remaining <= 0) {
                    flush(windowStart)
                    windowStart = deadline
                    currentWindowStart = windowStart
                    continue
                }

                drainInbox()
                delay(minOf(remaining, POLL_INTERVAL_MS))
            }
        } finally {
            counters.exitCounter.fetchAndIncrement()
        }
    }

    private fun drainInbox() {
        while (true) {
            val sample = inbox.tryReceive().getOrNull() ?: return
            aggregator.record(sample.method, sample.route, sample.status, sample.durationMs, sample.timestampMs)
        }
    }

    private suspend fun currentScopeIsActive(): Boolean = currentCoroutineContext().isActive

    private suspend fun flush(windowStart: Long) {
        counters.windowCounter.fetchAndIncrement()

        // Забираем всё, что успело прийти между последним опросом и границей окна: иначе замер
        // уехал бы в следующее окно, хотя запрос завершился в этом.
        drainInbox()

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
