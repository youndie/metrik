package ru.workinprogress.metrik.web

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import ru.workinprogress.metrik.api.AlertView
import ru.workinprogress.metrik.api.ServiceSummary
import ru.workinprogress.metrik.api.Step

/** Опрос раз в 30 с: окно агрегации минутное, real-time тут ничего не добавит (docs/research §Р6). */
internal const val REFRESH_MS = 30_000L

private const val SPARKLINE_WINDOW_MS = 60 * 60 * 1000L

/** Верхнеуровневый маршрут дашборда — что показано в области контента справа от рельса. */
private sealed interface Route {
    data object Overview : Route

    data object Alerts : Route

    data class Service(
        val summary: ServiceSummary,
        val tab: Int = 0,
    ) : Route
}

/**
 * Дашборд «Metrik Expressive»: рельс слева (см. [NavRail]) + контент справа, переключаемый без
 * перезагрузки — «назад» из старой навигации рельс заменяет собой (клик по «Обзор» или по другому
 * сервису в списке).
 */
@Composable
fun App(
    client: MetrikClient = remember { MetrikClient() },
    nowMs: () -> Long,
) {
    var services by remember { mutableStateOf<List<ServiceSummary>>(emptyList()) }
    var alerts by remember { mutableStateOf<List<AlertView>>(emptyList()) }
    var sparklines by remember { mutableStateOf<Map<Long, List<ChartPoint>>>(emptyMap()) }
    var error by remember { mutableStateOf<String?>(null) }
    // Отдельно от error: различает «ещё не получили первый ответ» и «сервисов правда нет» —
    // без этого флага пустой список на старте и пустой список после опроса выглядели бы одинаково.
    var loaded by remember { mutableStateOf(false) }
    var lastSuccessAt by remember { mutableStateOf<Long?>(null) }
    var nowTick by remember { mutableStateOf(nowMs()) }
    var route by remember { mutableStateOf<Route>(Route.Overview) }

    LaunchedEffect(Unit) {
        while (true) {
            runCatching {
                val freshServices = client.services()
                val freshAlerts = client.alerts()

                // Спарклайны карточек обзора — точки берём из отдельного timeseries на каждый
                // сервис: /api/services отдаёт только 5-минутные агрегаты, ряда там нет. Тянем это
                // лениво (только при живом опросе, не по требованию открытия карточки) и
                // параллельно — при небольшом числе сервисов на инсталляцию (см. финальный отчёт)
                // это остаётся одним раундом опроса раз в 30 с, а не N последовательных.
                val to = nowMs()
                val from = to - SPARKLINE_WINDOW_MS
                val deferredByService =
                    coroutineScope {
                        freshServices.map { service ->
                            service.id to
                                async {
                                    runCatching { client.timeSeries(service.id, from, to, Step.MINUTE) }.getOrNull()
                                }
                        }
                    }
                val freshSparklines = LinkedHashMap<Long, List<ChartPoint>>()
                for ((serviceId, deferred) in deferredByService) {
                    val points = deferred.await()?.points?.toChart { it.requestsPerSecond } ?: emptyList()
                    freshSparklines[serviceId] = points
                }

                services = freshServices
                alerts = freshAlerts
                sparklines = freshSparklines
                error = null
                lastSuccessAt = nowMs()
            }.onFailure { cause -> error = cause.message ?: "не удалось получить данные" }
            loaded = true
            delay(REFRESH_MS)
        }
    }

    // Секундный тик — только для надписи «обновлено N с назад» в рельсе, данные он не запрашивает.
    LaunchedEffect(Unit) {
        while (true) {
            nowTick = nowMs()
            delay(1000)
        }
    }

    MetrikTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceDim) {
            Row(Modifier.fillMaxSize()) {
                NavRail(
                    services = services,
                    firingAlertCount = alerts.count { it.state.equals("firing", ignoreCase = true) },
                    updatedAgoLabel = lastSuccessAt?.let { "обновлено ${relativeAgo(nowTick, it)} назад" } ?: "опрашиваем…",
                    topRoute =
                        when (route) {
                            Route.Overview -> TopRoute.OVERVIEW
                            Route.Alerts -> TopRoute.ALERTS
                            is Route.Service -> null
                        },
                    selectedServiceId = (route as? Route.Service)?.summary?.id,
                    onOverview = { route = Route.Overview },
                    onAlerts = { route = Route.Alerts },
                    onSelectService = { service -> route = Route.Service(service) },
                )

                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(topStart = 28.dp))
                        .background(MaterialTheme.colorScheme.surfaceDim)
                        .padding(horizontal = Spacing.xxl, vertical = Spacing.xl),
                ) {
                    if (error != null) {
                        ErrorBanner(error!!)
                    }

                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when (val current = route) {
                            Route.Overview -> {
                                OverviewScreen(services, alerts, sparklines, loaded) { service ->
                                    route = Route.Service(service)
                                }
                            }

                            Route.Alerts -> {
                                AlertsScreen(client, services, alerts) { nowTick }
                            }

                            is Route.Service -> {
                                // Сервис в маршруте мог устареть между опросами (например, у него
                                // изменился firingAlerts) — берём актуальную версию по id, а если
                                // сервис вдруг пропал из списка, используем последнюю известную,
                                // чтобы экран не схлопывался в пустоту посреди просмотра.
                                val fresh = services.firstOrNull { it.id == current.summary.id } ?: current.summary
                                ServiceScreen(client, fresh, alerts, { nowTick }, current.tab) { newTab ->
                                    route = current.copy(summary = fresh, tab = newTab)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(50),
        modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.md),
    ) {
        Text(
            "нет связи с сервером: $message",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
        )
    }
}
