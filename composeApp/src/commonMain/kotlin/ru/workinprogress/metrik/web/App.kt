package ru.workinprogress.metrik.web

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ru.workinprogress.metrik.api.AlertView
import ru.workinprogress.metrik.api.RouteRow
import ru.workinprogress.metrik.api.ServiceSummary
import ru.workinprogress.metrik.api.SlowRow
import ru.workinprogress.metrik.api.Step
import ru.workinprogress.metrik.api.SystemPoint
import ru.workinprogress.metrik.api.TimeSeries

private const val REFRESH_MS = 30_000L
private const val HOUR_MS = 60 * 60 * 1000L

/**
 * Дашборд.
 *
 * Обновление — опрос раз в 30 секунд: окно агрегации минутное, и real-time тут ничего не добавит
 * (docs/research §Р6).
 */
@Composable
fun App(
    client: MetrikClient = remember { MetrikClient() },
    nowMs: () -> Long,
) {
    var services by remember { mutableStateOf<List<ServiceSummary>>(emptyList()) }
    var alerts by remember { mutableStateOf<List<AlertView>>(emptyList()) }
    var selected by remember { mutableStateOf<ServiceSummary?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // Отдельно от error: различает «ещё не получили первый ответ» и «сервисов правда нет» —
    // без этого флага пустой список на старте и пустой список после опроса выглядели бы одинаково.
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            runCatching {
                services = client.services()
                alerts = client.alerts()
                error = null
            }.onFailure { cause -> error = cause.message ?: "не удалось получить данные" }
            loaded = true
            delay(REFRESH_MS)
        }
    }

    MetrikTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(Spacing.lg)) {
                Header(error)
                Spacer(Modifier.height(Spacing.lg))

                val current = selected
                if (current == null) {
                    OverviewScreen(services, alerts, loaded) { service -> selected = service }
                } else {
                    ServiceScreen(client, current, nowMs) { selected = null }
                }
            }
        }
    }
}

@Composable
private fun Header(error: String?) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "metrik",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (error != null) {
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(50)) {
                Text(
                    "нет связи с сервером: $error",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
                )
            }
        }
    }
}

@Composable
private fun OverviewScreen(
    services: List<ServiceSummary>,
    alerts: List<AlertView>,
    loaded: Boolean,
    onSelect: (ServiceSummary) -> Unit,
) {
    if (!loaded) {
        LoadingState("загружаем список сервисов…")
        return
    }

    if (services.isEmpty()) {
        EmptyState("Пока ни один сервис не прислал метрик")
        return
    }

    Column {
        if (alerts.isNotEmpty()) {
            AlertsBanner(alerts)
            Spacer(Modifier.height(Spacing.lg))
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            items(services) { service ->
                ServiceCard(service) { onSelect(service) }
            }
        }
    }
}

@Composable
private fun AlertsBanner(alerts: List<AlertView>) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                "Активные алерты (${alerts.size})",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            alerts.forEach { alert ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(Spacing.xs))
                    Text(
                        "${alert.service} — ${alert.ruleId}" + (alert.detail?.let { ": $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun ServiceCard(
    service: ServiceSummary,
    onClick: () -> Unit,
) {
    val firing = service.firingAlerts.isNotEmpty()

    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        border = if (firing) BorderStroke(1.dp, MaterialTheme.colorScheme.error) else null,
    ) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(service.name, style = MaterialTheme.typography.titleMedium)
                Pill("${service.instances} инст.")
            }

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
                MetricStat("rps", format(service.requestsPerSecond))
                MetricStat("ошибки", "${format(service.errorRate * 100)}%")
                MetricStat("p95 ≈", "${format(service.p95Ms)} мс")
            }

            if (firing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(Spacing.xs))
                    Text(
                        service.firingAlerts.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (service.clockSkew) {
                Text(
                    "часы инстанса разъехались — часть окон отброшена",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
private fun ServiceScreen(
    client: MetrikClient,
    service: ServiceSummary,
    nowMs: () -> Long,
    onBack: () -> Unit,
) {
    var tab by remember { mutableStateOf(0) }
    var series by remember { mutableStateOf<TimeSeries?>(null) }
    var routes by remember { mutableStateOf<List<RouteRow>>(emptyList()) }
    var slow by remember { mutableStateOf<List<SlowRow>>(emptyList()) }
    var system by remember { mutableStateOf<List<SystemPoint>>(emptyList()) }
    var loaded by remember(service.id) { mutableStateOf(false) }

    LaunchedEffect(service.id) {
        while (true) {
            val to = nowMs()
            val from = to - HOUR_MS
            runCatching {
                series = client.timeSeries(service.id, from, to, Step.MINUTE)
                routes = client.routes(service.id, from, to)
                slow = client.slow(service.id)
                system = client.system(service.id, from, to)
            }
            loaded = true
            delay(REFRESH_MS)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← назад") }
            Spacer(Modifier.width(Spacing.sm))
            Text(service.name, style = MaterialTheme.typography.titleLarge)
        }

        Spacer(Modifier.height(Spacing.sm))

        val titles = listOf("Графики", "Маршруты", "Медленные", "Система")
        SecondaryTabRow(selectedTabIndex = tab) {
            titles.forEachIndexed { index, title ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
            }
        }

        Box(Modifier.padding(top = Spacing.md).verticalScroll(rememberScrollState())) {
            when (tab) {
                0 -> ChartsTab(series, loaded)
                1 -> RoutesTab(routes, loaded)
                2 -> SlowTab(slow, loaded)
                else -> SystemTab(system, loaded)
            }
        }
    }
}

@Composable
private fun ChartsTab(
    series: TimeSeries?,
    loaded: Boolean,
) {
    if (series == null) {
        if (loaded) EmptyState("нет данных") else LoadingState("загружаем графики…")
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (series.step != Step.MINUTE) {
            Text(
                "данные часовые: минутные окна за этот период уже удалены",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LineChart("Запросы в секунду", series.points.toChart { it.requestsPerSecond }, series.deploys)
        LineChart("p95, мс (приблизительно, ±20 %)", series.points.toChart { it.p95Ms }, series.deploys)
        LineChart("p50, мс (приблизительно, ±20 %)", series.points.toChart { it.p50Ms }, series.deploys)
        LineChart(
            "Доля ошибок, %",
            series.points.toChart { it.errorRate * 100 },
            series.deploys,
            color = MaterialTheme.colorScheme.error,
        )

        if (series.points.any { it.partial }) {
            Text(
                "разрывы на графиках — окна, от которых пришли не все пакеты",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RoutesTab(
    routes: List<RouteRow>,
    loaded: Boolean,
) {
    if (routes.isEmpty()) {
        if (loaded) EmptyState("нет данных") else LoadingState("загружаем маршруты…")
        return
    }

    Column {
        Text(
            "Перцентили приблизительные: погрешность не больше ширины бакета (20 %)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.sm))

        RouteHeaderRow()
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        routes.forEach { row ->
            RouteRowItem(row)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun RouteHeaderRow() {
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
        Text(
            "Маршрут",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(2.4f),
        )
        RouteHeaderCell("Статус")
        RouteHeaderCell("Кол-во")
        RouteHeaderCell("p50")
        RouteHeaderCell("p95")
        RouteHeaderCell("max")
    }
}

@Composable
private fun RowScope.RouteHeaderCell(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.weight(0.85f),
    )
}

@Composable
private fun RouteRowItem(row: RouteRow) {
    val statusColor = statusColor(row.status)

    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${row.method} ${row.route}",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(2.4f),
        )
        Text(
            row.status.toString(),
            style = MaterialTheme.typography.bodySmall,
            color = statusColor,
            modifier = Modifier.weight(0.85f),
        )
        Text(row.count.toString(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.85f))
        Text(format(row.p50Ms), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.85f))
        Text(format(row.p95Ms), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.85f))
        Text(row.maxMs.toString(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.85f))
    }
}

@Composable
private fun SlowTab(
    slow: List<SlowRow>,
    loaded: Boolean,
) {
    if (slow.isEmpty()) {
        if (loaded) EmptyState("нет данных") else LoadingState("загружаем медленные запросы…")
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        slow.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "${row.method} ${row.route}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "${row.durationMs} мс · ${row.status}",
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor(row.status),
                )
            }
        }
    }
}

@Composable
private fun SystemTab(
    system: List<SystemPoint>,
    loaded: Boolean,
) {
    if (system.isEmpty()) {
        if (loaded) EmptyState("нет данных") else LoadingState("загружаем системные метрики…")
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        system.groupBy { it.instance }.forEach { (instance, points) ->
            val last = points.last()
            // У нативного процесса нет верхней границы heap в смысле JVM — то, что мы видим, это
            // RSS всего процесса, а не память управляемой кучи. Подписывать это «heap» было бы
            // враньём (docs/services/metrik-web.md, §4).
            val memoryLabel = if (last.heapMaxBytes == null) "RSS" else "heap"
            val limit = last.heapMaxBytes?.let { " / ${it / 1024 / 1024} МБ" } ?: ""

            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(instance, style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
                    MetricStat(memoryLabel, "${last.heapUsedBytes / 1024 / 1024} МБ$limit")
                    MetricStat("CPU", "${last.cpuPermille / 10.0}%")
                    MetricStat("тредов", last.threads.toString())
                    MetricStat("GC", last.gcCollections?.toString() ?: "нет данных")
                }
                LineChart(
                    "$memoryLabel, МБ — $instance",
                    points.map { ChartPoint(it.at, it.heapUsedBytes / 1024.0 / 1024.0) },
                )
            }
        }
    }
}

/** Мелкая подпись «значение над лейблом» — единый вид числовых показателей по всему дашборду. */
@Composable
private fun MetricStat(
    label: String,
    value: String,
) {
    Column {
        Text(value, style = MaterialTheme.typography.titleSmall)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Скруглённая бирка (число инстансов и т. п.) — нейтральная, не претендует на статус алерта. */
@Composable
private fun Pill(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(50)) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp),
        )
    }
}

@Composable
private fun StatusDot(color: Color) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
}

@Composable
private fun LoadingState(text: String) {
    Column(Modifier.fillMaxWidth().padding(top = Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(Modifier.size(28.dp))
        Spacer(Modifier.height(Spacing.sm))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyState(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.xl),
    )
}

/**
 * Цвет статус-кода: 5xx — ошибка сервера, 4xx — ошибка клиента, остальное — нейтрально.
 * Граница взята из самого кода ответа, а не из подобранного на глаз порога.
 */
@Composable
private fun statusColor(status: Int): Color =
    when {
        status >= 500 -> MaterialTheme.colorScheme.error
        status >= 400 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
