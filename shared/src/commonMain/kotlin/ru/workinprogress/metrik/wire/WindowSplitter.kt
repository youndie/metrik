package ru.workinprogress.metrik.wire

// Нарезка закрытого окна на датаграммы.
// Лимит в 1200 байт — не ограничение Ktor, а MTU: фрагментированная датаграмма теряется целиком
// (docs/api/protocol-ingest.md, «Инварианты»).

/** Неизменная часть окна — одинаковая во всех его пакетах. */
class WindowHeader(
    val apiKey: String,
    val service: String,
    val instance: String,
    val windowStart: Long,
    val windowSeq: Long,
    val release: String? = null,
    val windowMs: Long = DEFAULT_WINDOW_MS,
)

/**
 * Результат нарезки.
 *
 * [oversized] — сколько пакетов не влезли в лимит. Такое возможно, только если одна серия сама по
 * себе больше бюджета (аномально длинный шаблон маршрута): резать серию нельзя, выбрасывать данные
 * молча — тем более, поэтому пакет уходит как есть, а счётчик даёт агенту повод это заметить.
 */
class WindowSplit(
    val packets: List<String>,
    val oversized: Int,
)

private const val PROBE_PACKET_INDEX = 999
private const val PROBE_PACKET_COUNT = 999

private class PacketPlan(
    val routes: List<RouteSeries>,
    val system: SystemSnapshot?,
    val slow: List<SlowSample>?,
)

/**
 * Режет окно на пакеты не длиннее [maxPacketBytes].
 *
 * Раскладка по пакетам фиксирована контрактом: системный срез едет в пакете `q = 0`,
 * медленные сэмплы — в последнем.
 */
fun splitWindow(
    header: WindowHeader,
    routes: List<RouteSeries>,
    system: SystemSnapshot? = null,
    slow: List<SlowSample> = emptyList(),
    maxPacketBytes: Int = MAX_PACKET_BYTES,
): WindowSplit {
    val chunks = chunkRoutes(header, routes, system, maxPacketBytes)

    val plans =
        chunks
            .mapIndexed { index, chunk ->
                PacketPlan(routes = chunk, system = if (index == 0) system else null, slow = null)
            }.toMutableList()

    if (slow.isNotEmpty()) {
        val last = plans.last()
        val withSlow = PacketPlan(last.routes, last.system, slow)

        if (sizeOf(frameOf(header, withSlow, PROBE_PACKET_INDEX, PROBE_PACKET_COUNT, includeRelease = true)) <= maxPacketBytes ||
            last.routes.isEmpty()
        ) {
            plans[plans.lastIndex] = withSlow
        } else {
            // Сэмплы не влезли к последним сериям — уезжают отдельным пакетом, он и станет последним.
            plans += PacketPlan(routes = emptyList(), system = null, slow = slow)
        }
    }

    val packets =
        plans.mapIndexed { index, plan ->
            MetrikJson.encodeToString(frameOf(header, plan, index, plans.size))
        }

    return WindowSplit(
        packets = packets,
        oversized = packets.count { it.encodeToByteArray().size > maxPacketBytes },
    )
}

private fun chunkRoutes(
    header: WindowHeader,
    routes: List<RouteSeries>,
    system: SystemSnapshot?,
    maxPacketBytes: Int,
): List<List<RouteSeries>> {
    if (routes.isEmpty()) return listOf(emptyList())

    val chunks = mutableListOf<List<RouteSeries>>()
    var current = mutableListOf<RouteSeries>()

    routes.forEach { series ->
        current += series

        val probe =
            frameOf(
                header = header,
                plan = PacketPlan(current, if (chunks.isEmpty()) system else null, null),
                packetIndex = PROBE_PACKET_INDEX,
                packetCount = PROBE_PACKET_COUNT,
                includeRelease = true,
            )

        // Серия, не влезающая в пакет в одиночку, всё равно отправляется: см. WindowSplit.oversized.
        if (sizeOf(probe) > maxPacketBytes && current.size > 1) {
            current.removeAt(current.lastIndex)
            chunks += current.toList()
            current = mutableListOf(series)
        }
    }

    chunks += current.toList()
    return chunks
}

private fun frameOf(
    header: WindowHeader,
    plan: PacketPlan,
    packetIndex: Int,
    packetCount: Int,
    // Пробный пакет обязан быть не меньше реального, поэтому при замере release включается всегда,
    // хотя на проводе он едет только в q = 0.
    includeRelease: Boolean = packetIndex == 0,
): Frame =
    Frame(
        apiKey = header.apiKey,
        service = header.service,
        instance = header.instance,
        release = if (includeRelease) header.release else null,
        windowStart = header.windowStart,
        windowMs = header.windowMs,
        windowSeq = header.windowSeq,
        packetIndex = packetIndex,
        packetCount = packetCount,
        routes = plan.routes,
        system = plan.system,
        slow = plan.slow,
    )

private fun sizeOf(frame: Frame): Int = MetrikJson.encodeToString(frame).encodeToByteArray().size
