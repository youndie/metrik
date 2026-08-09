package ru.workinprogress.metrik.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Модель ingest-пакета. Поля намеренно однобуквенные: при 1200 байтах на датаграмму длина ключей —
// это количество серий, которое влезет. Полное описание — docs/api/protocol-ingest.md.

/**
 * Серия по маршруту за окно.
 *
 * Ключ серии — `(method, route, status)`. `route` — **шаблон** (`/users/{id}`), а не путь запроса:
 * сырой путь взорвал бы кардинальность.
 */
@Serializable
data class RouteSeries(
    @SerialName("m") val method: String,
    @SerialName("p") val route: String,
    @SerialName("c") val status: Int,
    @SerialName("n") val count: Int,
    @SerialName("s") val sumMs: Long,
    @SerialName("x") val maxMs: Int,
    @SerialName("b") val buckets: Histogram,
)

/** Счётчики сборщика мусора. Есть не на всякой платформе — отсутствие поля это не ошибка. */
@Serializable
data class GcSnapshot(
    @SerialName("c") val collections: Int,
    @SerialName("t") val totalMs: Long,
)

/**
 * Срез состояния процесса, снимается раз в окно.
 *
 * `heapMaxBytes` необязателен: у нативного процесса нет «максимума heap» в смысле JVM,
 * туда подставляется лимит cgroup, если он читается.
 */
@Serializable
data class SystemSnapshot(
    /**
     * Рантайм процесса: `jvm` или `native`.
     *
     * Передаётся явно, потому что вывести его из остальных полей нельзя. Раньше дашборд считал
     * нативным того, у кого нет `heapMaxBytes` — но в контейнере нативный агент кладёт туда лимит
     * cgroup, и все нативные сервисы подписывались как JVM, а RSS выдавался за heap.
     *
     * `null` — старый агент, который поле ещё не шлёт. Это «неизвестно», а не «JVM».
     */
    @SerialName("rt") val runtime: String? = null,
    @SerialName("hu") val heapUsedBytes: Long,
    @SerialName("hm") val heapMaxBytes: Long? = null,
    @SerialName("cp") val cpuPermille: Int,
    @SerialName("th") val threads: Int,
    @SerialName("up") val uptimeSeconds: Long,
    @SerialName("gc") val gc: GcSnapshot? = null,
)

/**
 * Один из самых медленных запросов окна.
 *
 * Сознательно не содержит тела, заголовков, query string и сырого пути: metrik не должен
 * становиться местом, где случайно оседают персональные данные.
 */
@Serializable
data class SlowSample(
    @SerialName("m") val method: String,
    @SerialName("p") val route: String,
    @SerialName("c") val status: Int,
    @SerialName("ms") val durationMs: Int,
    @SerialName("t") val timestamp: Long,
)

/**
 * Датаграмма. Самодостаточна: потеря любого пакета не мешает разобрать остальные.
 *
 * `windowSeq` и пара `packetIndex`/`packetCount` — единственный способ отличить «сервис молчал»
 * от «пакеты не долетели»; сервер обязан их использовать.
 */
@Serializable
data class Frame(
    @SerialName("v") val version: Int = PROTOCOL_VERSION,
    @SerialName("k") val apiKey: String,
    @SerialName("s") val service: String,
    @SerialName("i") val instance: String,
    @SerialName("rel") val release: String? = null,
    @SerialName("t") val windowStart: Long,
    @SerialName("d") val windowMs: Long = DEFAULT_WINDOW_MS,
    @SerialName("w") val windowSeq: Long,
    @SerialName("q") val packetIndex: Int,
    @SerialName("n") val packetCount: Int,
    @SerialName("r") val routes: List<RouteSeries> = emptyList(),
    @SerialName("y") val system: SystemSnapshot? = null,
    @SerialName("x") val slow: List<SlowSample>? = null,
)

/**
 * Кодек провода, общий для агента и сервера.
 *
 * `ignoreUnknownKeys` — часть контракта совместимости: аддитивные поля агент может слать сразу,
 * не поднимая версию протокола. `explicitNulls = false` убирает из пакета `null`-поля, за которые
 * незачем платить байтами.
 */
val MetrikJson: Json =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
