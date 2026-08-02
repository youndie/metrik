package ru.workinprogress.metrik.web

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.text.font.FontFamily
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

    LaunchedEffect(Unit) {
        while (true) {
            runCatching {
                services = client.services()
                alerts = client.alerts()
                error = null
            }.onFailure { cause -> error = cause.message ?: "не удалось получить данные" }
            delay(REFRESH_MS)
        }
    }

    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(16.dp)) {
                Header(error)

                val current = selected
                if (current == null) {
                    OverviewScreen(services, alerts) { service -> selected = service }
                } else {
                    ServiceScreen(client, current, nowMs) { selected = null }
                }
            }
        }
    }
}

@Composable
private fun Header(error: String?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("metrik", style = MaterialTheme.typography.headlineSmall)
        if (error != null) {
            Text(
                "  · $error",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun OverviewScreen(
    services: List<ServiceSummary>,
    alerts: List<AlertView>,
    onSelect: (ServiceSummary) -> Unit,
) {
    if (alerts.isNotEmpty()) {
        Text("Активные алерты", style = MaterialTheme.typography.titleMedium)
        alerts.forEach { alert ->
            Text("🔴 ${alert.service} — ${alert.ruleId}", color = MaterialTheme.colorScheme.error)
        }
    }

    if (services.isEmpty()) {
        Text("Пока ни один сервис не прислал метрик", Modifier.padding(top = 16.dp))
        return
    }

    LazyColumn(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(services) { service ->
            Card(Modifier.fillMaxWidth().clickable { onSelect(service) }) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(service.name, style = MaterialTheme.typography.titleMedium)
                        Text("${service.instances} инст.", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "${format(service.requestsPerSecond)} rps · " +
                            "ошибки ${format(service.errorRate * 100)}% · " +
                            "p95 ≈ ${format(service.p95Ms)} мс",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (service.clockSkew) {
                        Text(
                            "часы инстанса разъехались — часть окон отброшена",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
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
            delay(REFRESH_MS)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← назад") }
            Text(service.name, style = MaterialTheme.typography.titleLarge)
        }

        val titles = listOf("Графики", "Маршруты", "Медленные", "Система")
        TabRow(selectedTabIndex = tab) {
            titles.forEachIndexed { index, title ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
            }
        }

        Box(Modifier.padding(top = 12.dp).verticalScroll(rememberScrollState())) {
            when (tab) {
                0 -> ChartsTab(series)
                1 -> RoutesTab(routes)
                2 -> SlowTab(slow)
                else -> SystemTab(system)
            }
        }
    }
}

@Composable
private fun ChartsTab(series: TimeSeries?) {
    if (series == null) {
        Text("загрузка…")
        return
    }

    Column {
        if (series.step != Step.MINUTE) {
            Text(
                "данные часовые: минутные окна за этот период уже удалены",
                style = MaterialTheme.typography.bodySmall,
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
            )
        }
    }
}

@Composable
private fun RoutesTab(routes: List<RouteRow>) {
    if (routes.isEmpty()) {
        Text("нет данных")
        return
    }

    Column {
        Text(
            "Перцентили приблизительные: погрешность не больше ширины бакета (20 %)",
            style = MaterialTheme.typography.bodySmall,
        )
        routes.forEach { row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("${row.method} ${row.route}", fontFamily = FontFamily.Monospace)
                Text(
                    "${row.status} · ${row.count} · p50 ${format(row.p50Ms)} · " +
                        "p95 ${format(row.p95Ms)} · max ${row.maxMs}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SlowTab(slow: List<SlowRow>) {
    if (slow.isEmpty()) {
        Text("нет данных")
        return
    }

    Column {
        slow.forEach { row ->
            Text(
                "${row.durationMs} мс · ${row.method} ${row.route} · ${row.status}",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SystemTab(system: List<SystemPoint>) {
    if (system.isEmpty()) {
        Text("нет данных")
        return
    }

    Column {
        system.groupBy { it.instance }.forEach { (instance, points) ->
            val last = points.last()
            val memoryLabel = if (last.heapMaxBytes == null) "RSS" else "heap"
            val limit = last.heapMaxBytes?.let { " / ${it / 1024 / 1024} МБ" } ?: ""

            Text(instance, style = MaterialTheme.typography.titleSmall)
            Text(
                "$memoryLabel ${last.heapUsedBytes / 1024 / 1024} МБ$limit · " +
                    "CPU ${last.cpuPermille / 10.0}% · тредов ${last.threads} · " +
                    "GC ${last.gcCollections?.toString() ?: "нет данных"}",
                style = MaterialTheme.typography.bodySmall,
            )
            LineChart(
                "$memoryLabel, МБ — $instance",
                points.map { ChartPoint(it.at, it.heapUsedBytes / 1024.0 / 1024.0) },
            )
        }
    }
}
