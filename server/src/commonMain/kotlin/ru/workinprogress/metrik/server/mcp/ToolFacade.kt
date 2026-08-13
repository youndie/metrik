package ru.workinprogress.metrik.server.mcp

import ru.workinprogress.metrik.api.AlertView
import ru.workinprogress.metrik.api.DeployMarker
import ru.workinprogress.metrik.api.Overview
import ru.workinprogress.metrik.api.RouteRow
import ru.workinprogress.metrik.api.ServiceSummary
import ru.workinprogress.metrik.api.Step
import ru.workinprogress.metrik.api.TimeSeries
import ru.workinprogress.metrik.api.isFiring
import ru.workinprogress.metrik.server.alert.AlertWorker
import ru.workinprogress.metrik.server.query.QueryService

/**
 * Что именно инструменты MCP умеют спросить у metrik.
 *
 * Слой существует по двум причинам. Во-первых, агент оперирует **именами** сервисов, а не
 * числовыми id: `metrik-server`, а не `7`. Разрешение имени в id — здесь, а не в описании каждого
 * инструмента. Во-вторых, у фасада есть тест, а у регистрации инструментов — нет: там только
 * описания и разбор аргументов.
 *
 * Логики запросов здесь нет и быть не должно — она в [QueryService], общая с HTTP-API.
 * Разошедшиеся ответы у дашборда и у агента — это два разных мнения об одном и том же инциденте.
 */
class ToolFacade(
    private val query: QueryService,
    private val alerts: AlertWorker,
) {
    suspend fun listServices(): List<ServiceSummary> = query.services()

    suspend fun overview(
        service: String,
        from: Long,
        to: Long,
    ): Overview = query.overview(serviceId(service), from, to)

    /**
     * Маршруты, отсортированные по времени, а не по количеству.
     *
     * `QueryService.routes` сортирует по частоте — это верно для таблицы на дашборде, где человек
     * ищет глазами. Инструмент зовут с вопросом «что тормозит», и самый частый маршрут обычно
     * самый быстрый.
     */
    suspend fun slowRoutes(
        service: String,
        from: Long,
        to: Long,
        limit: Int,
    ): List<RouteRow> =
        query
            .routes(serviceId(service), from, to)
            .sortedByDescending { it.p95Ms }
            .take(limit)

    /** Только 5xx: 4xx — это про клиента, и в вопросе «что у нас сломалось» они шум. */
    suspend fun serverErrors(
        service: String,
        from: Long,
        to: Long,
        limit: Int,
    ): List<RouteRow> =
        query
            .routes(serviceId(service), from, to)
            .filter { it.status >= 500 }
            .sortedByDescending { it.count }
            .take(limit)

    /**
     * Ряд по сервису.
     *
     * Шаг запрашивается, но не гарантируется: минутные окна живут ограниченное время, и за
     * пределами ретенции сервер молча отдаёт часовой. Что он отдал на самом деле — в `step`
     * ответа, и об этом сказано в описании инструмента: агент, считающий шаг тем, что попросил,
     * ошибётся в арифметике по времени.
     */
    suspend fun timeSeries(
        service: String,
        from: Long,
        to: Long,
        step: Step,
    ): TimeSeries = query.timeSeries(serviceId(service), from, to, step)

    suspend fun deploys(
        service: String,
        from: Long,
        to: Long,
    ): List<DeployMarker> = query.deploys(serviceId(service), from, to)

    suspend fun firingAlerts(): List<AlertView> = alerts.active().filter { it.isFiring }

    /**
     * Имя → id, с внятной ошибкой вместо пустого ответа.
     *
     * Пустой список в ответ на опечатку в имени агент читает как «проблем нет», и это худший из
     * возможных исходов для инструмента диагностики. Поэтому здесь исключение с перечислением
     * того, что есть на самом деле.
     */
    private suspend fun serviceId(name: String): Long =
        query.serviceIdByName(name)
            ?: error(
                "нет сервиса с именем `$name`; известные: " +
                    query.services().joinToString { it.name },
            )
}
