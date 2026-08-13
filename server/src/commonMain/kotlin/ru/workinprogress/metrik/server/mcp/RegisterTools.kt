package ru.workinprogress.metrik.server.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import ru.workinprogress.metrik.api.Step
import ru.workinprogress.metrik.wire.MetrikJson

/** Сколько строк максимум отдаёт один инструмент. Больше агенту всё равно нечем распорядиться. */
private const val DEFAULT_LIMIT = 20
private const val MAX_LIMIT = 100

/**
 * Шесть инструментов чтения поверх [ToolFacade].
 *
 * Описания здесь — часть контракта, а не украшение. Агент, которому не сказали, что `slow_routes`
 * сортирует по p95, а не по частоте, будет читать первую строку как «самый нагруженный маршрут».
 * Агент, которому не сказали, что окно обязательно, попросит «всё» и получит полный скан.
 */
internal fun Server.registerTools(facade: ToolFacade) {
    val readOnly = ToolAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false)

    fun schema(
        required: List<String> = emptyList(),
        properties: JsonObject,
    ) = ToolSchema(properties = properties, required = required.takeIf { it.isNotEmpty() })

    fun ok(payload: String) = CallToolResult(content = listOf(TextContent(payload)))

    fun args(request: CallToolRequest): JsonObject = request.params.arguments ?: JsonObject(emptyMap())

    fun JsonObject.str(name: String): String? = this[name]?.jsonPrimitive?.content

    fun JsonObject.num(name: String): Long? = this[name]?.jsonPrimitive?.content?.toLongOrNull()

    fun JsonObject.service(): String = str("service") ?: error("`service` обязателен: имя как в `list_services`")

    // Окно обязательно у каждого инструмента, который его принимает. Умолчание «за всё время»
    // читается как удобство, а ведёт себя как полный скан по всем окнам, что есть в базе.
    fun JsonObject.from(): Long = num("from") ?: error("`from` обязателен: без окна чтение ничем не ограничено")

    fun JsonObject.to(): Long = num("to") ?: error("`to` обязателен: конец окна, epoch millis")

    fun JsonObject.limit(): Int = (num("limit")?.toInt() ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

    val window =
        buildJsonObject {
            putJsonObject("service") {
                put("type", "string")
                put("description", "имя сервиса — то же, что в `list_services`; не числовой id")
            }
            putJsonObject("from") {
                put("type", "integer")
                put("description", "начало окна, epoch millis; обязателен — чтение без окна ничем не ограничено")
            }
            putJsonObject("to") {
                put("type", "integer")
                put("description", "конец окна, epoch millis")
            }
        }

    val limited =
        buildJsonObject {
            putJsonObject("limit") {
                put("type", "integer")
                put("description", "сколько строк вернуть, по умолчанию $DEFAULT_LIMIT, максимум $MAX_LIMIT")
            }
        }

    val windowed = listOf("service", "from", "to")

    addTool(
        name = "list_services",
        description =
            "Сервисы под наблюдением за последние пять минут: запросов в секунду, доля ошибок, p95, " +
                "число инстансов, время последнего пакета и список горящих правил. " +
                "Отсюда берутся имена для остальных инструментов. " +
                "`clockSkew` = true означает, что часы инстанса разъехались с сервером: цифры по нему " +
                "и есть цифры, но время у них своё.",
        inputSchema = schema(properties = JsonObject(emptyMap())),
        toolAnnotations = readOnly,
    ) { _ ->
        ok(MetrikJson.encodeToString(facade.listServices()))
    }

    addTool(
        name = "service_overview",
        description =
            "Итог по сервису за окно: сколько запросов и ошибок, доля ошибок, p50, p95 и максимум. " +
                "Первое, что стоит спросить, когда известно имя сервиса и подозрительный интервал.",
        inputSchema = schema(required = windowed, properties = window),
        toolAnnotations = readOnly,
    ) { request ->
        val a = args(request)
        ok(MetrikJson.encodeToString(facade.overview(a.service(), a.from(), a.to())))
    }

    addTool(
        name = "slow_routes",
        description =
            "Маршруты сервиса, отсортированные по p95 — то есть по тому, что тормозит, " +
                "а не по тому, что чаще всего вызывают. Самый частый маршрут обычно самый быстрый, " +
                "и сортировка по частоте на вопрос «что медленно» не отвечает.",
        inputSchema = schema(required = windowed, properties = JsonObject(window + limited)),
        toolAnnotations = readOnly,
    ) { request ->
        val a = args(request)
        ok(MetrikJson.encodeToString(facade.slowRoutes(a.service(), a.from(), a.to(), a.limit())))
    }

    addTool(
        name = "server_errors",
        description =
            "Только 5xx по маршрутам сервиса за окно, по убыванию количества. " +
                "4xx сюда не попадают намеренно: это про клиента, а не про сервис, " +
                "и в вопросе «что у нас сломалось» они шум.",
        inputSchema = schema(required = windowed, properties = JsonObject(window + limited)),
        toolAnnotations = readOnly,
    ) { request ->
        val a = args(request)
        ok(MetrikJson.encodeToString(facade.serverErrors(a.service(), a.from(), a.to(), a.limit())))
    }

    addTool(
        name = "time_series",
        description =
            "Ряд по сервису: запросов в секунду, доля ошибок, p50, p95 и максимум по шагам. " +
                "Шаг запрашивается (`minute`, `hour`, `day`), но не гарантируется: минутные окна живут " +
                "ограниченное время, и за пределами ретенции сервер отдаёт часовой. Каким шагом данные " +
                "собраны на самом деле — в поле `step` ответа, считать надо по нему. " +
                "`partial: true` у точки означает, что окно неполное: данных меньше, а не нагрузки — " +
                "такую точку нельзя складывать с соседними и нельзя считать провалом трафика.",
        inputSchema =
            schema(
                required = windowed,
                properties =
                    JsonObject(
                        window +
                            buildJsonObject {
                                putJsonObject("step") {
                                    put("type", "string")
                                    put("description", "`minute`, `hour` или `day`; по умолчанию minute")
                                }
                            },
                    ),
            ),
        toolAnnotations = readOnly,
    ) { request ->
        val a = args(request)
        val step =
            when (a.str("step")?.lowercase()) {
                "hour" -> Step.HOUR
                "day" -> Step.DAY
                else -> Step.MINUTE
            }
        ok(MetrikJson.encodeToString(facade.timeSeries(a.service(), a.from(), a.to(), step)))
    }

    addTool(
        name = "deploys",
        description =
            "Версии, замеченные у сервиса в окне, с моментом первого появления. " +
                "Отвечает на вопрос «сломалось после релиза или само»: если ухудшение началось " +
                "после отметки — совпадение по времени есть, причинность всё ещё надо доказать.",
        inputSchema = schema(required = windowed, properties = window),
        toolAnnotations = readOnly,
    ) { request ->
        val a = args(request)
        ok(MetrikJson.encodeToString(facade.deploys(a.service(), a.from(), a.to())))
    }

    addTool(
        name = "firing_alerts",
        description =
            "Правила, горящие прямо сейчас, по всем сервисам, с моментом срабатывания. " +
                "Заглушенное правило (`mutedUntil` в будущем) продолжает гореть и остаётся здесь: " +
                "заглушение убирает уведомление, а не проблему.",
        inputSchema = schema(properties = JsonObject(emptyMap())),
        toolAnnotations = readOnly,
    ) { _ ->
        ok(MetrikJson.encodeToString(facade.firingAlerts()))
    }
}
