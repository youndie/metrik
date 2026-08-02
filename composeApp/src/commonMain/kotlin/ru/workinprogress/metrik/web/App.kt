package ru.workinprogress.metrik.web

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ru.workinprogress.metrik.api.AlertView
import ru.workinprogress.metrik.api.ServiceSummary

/** Опрос раз в 30 с: окно агрегации минутное, real-time тут ничего не добавит (docs/research §Р6). */
internal const val REFRESH_MS = 30_000L

/**
 * Порог переключения десктоп/мобильной раскладки — по ширине окна ([BoxWithConstraints]), а не по
 * платформе: дашборд открывают и в узком окне на десктопе (сплит-экран, недоразвёрнутое окно), и
 * оно должно вести себя как мобильное.
 *
 * Мобильные кадры макета — 390–420dp, десктопный рельс (268dp) + читаемая трёхколоночная сетка
 * карточек начинают требовать от ~900dp. 760dp — примерно середина между «точно телефон» и «точно
 * десктоп»: ниже него ни рельс, ни трёхколоночная сетка не проживут без обрезания контента, выше —
 * это уже полноценное десктопное окно, просто маленькое.
 */
private val MOBILE_MAX_WIDTH: Dp = 760.dp

/**
 * Отступы контента экранов.
 *
 * Передаются внутрь прокрутки, а не вешаются на контейнер: снаружи они сужают вьюпорт, и контент
 * обрезается по внутренней границе вместо того, чтобы уезжать под край.
 */
private val DesktopContentPadding = PaddingValues(horizontal = Spacing.xxl, vertical = Spacing.xl)
private val MobileContentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.lg)

/** Верхнеуровневый маршрут дашборда — что показано в области контента справа от рельса (десктоп)
 * или под нижней навигацией (мобильная раскладка). */
private sealed interface Route {
    data object Overview : Route

    data object Alerts : Route

    /** Список сервисов на весь экран — мобильный аналог списка в рельсе, третья вкладка нижней навигации. */
    data object Services : Route

    data class Service(
        val summary: ServiceSummary,
        val tab: Int = 0,
    ) : Route
}

/**
 * Дашборд «Metrik Expressive».
 *
 * Десктоп: рельс слева (см. [NavRail]) + контент справа, переключаемый без перезагрузки. Мобильная
 * раскладка (`maxWidth < `[MOBILE_MAX_WIDTH]): рельса нет, вместо него нижняя навигация из трёх
 * вкладок (Обзор / Алерты / Сервисы), контент — один столбец. Список сервисов и активные алерты
 * общие для обеих раскладок и обновляются одним и тем же опросом раз в 30 с; у «Обзора» на выбор
 * диапазона — свой независимый запрос (см. [OverviewScreen]).
 */
@Composable
fun App(
    client: MetrikClient = remember { MetrikClient() },
    nowMs: () -> Long,
) {
    var services by remember { mutableStateOf<List<ServiceSummary>>(emptyList()) }
    var alerts by remember { mutableStateOf<List<AlertView>>(emptyList()) }
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
                services = client.services()
                alerts = client.alerts()
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

    val firingAlertCount = alerts.count { it.state.equals("firing", ignoreCase = true) }

    MetrikTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceDim) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                if (maxWidth < MOBILE_MAX_WIDTH) {
                    MobileShell(
                        client = client,
                        services = services,
                        alerts = alerts,
                        error = error,
                        firingAlertCount = firingAlertCount,
                        nowTick = { nowTick },
                        route = route,
                        onRouteChange = { route = it },
                    )
                } else {
                    DesktopShell(
                        client = client,
                        services = services,
                        alerts = alerts,
                        error = error,
                        updatedAgoLabel = lastSuccessAt?.let { "обновлено ${relativeAgo(nowTick, it)} назад" } ?: "опрашиваем…",
                        nowTick = { nowTick },
                        route = route,
                        onRouteChange = { route = it },
                    )
                }
            }
        }
    }
}

@Composable
private fun DesktopShell(
    client: MetrikClient,
    services: List<ServiceSummary>,
    alerts: List<AlertView>,
    error: String?,
    updatedAgoLabel: String,
    nowTick: () -> Long,
    route: Route,
    onRouteChange: (Route) -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        NavRail(
            services = services,
            firingAlertCount = alerts.count { it.state.equals("firing", ignoreCase = true) },
            updatedAgoLabel = updatedAgoLabel,
            topRoute =
                when (route) {
                    Route.Overview -> TopRoute.OVERVIEW
                    Route.Alerts -> TopRoute.ALERTS
                    Route.Services, is Route.Service -> null
                },
            selectedServiceId = (route as? Route.Service)?.summary?.id,
            onOverview = { onRouteChange(Route.Overview) },
            onAlerts = { onRouteChange(Route.Alerts) },
            onSelectService = { service -> onRouteChange(Route.Service(service)) },
        )

        // Паддинга здесь нет намеренно: он уходит внутрь прокрутки каждого экрана
        // (contentPadding). Снаружи он сужал бы вьюпорт, и контент обрезался бы по внутренней
        // границе — выглядело так, будто экран скроллится внутри рамки.
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(topStart = 28.dp))
                .background(MaterialTheme.colorScheme.surfaceDim),
        ) {
            if (error != null) {
                Box(Modifier.padding(horizontal = Spacing.xxl, vertical = Spacing.md)) { ErrorBanner(error) }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (val current = route) {
                    Route.Overview -> {
                        OverviewScreen(
                            client = client,
                            alerts = alerts,
                            nowMs = nowTick,
                            contentPadding = DesktopContentPadding,
                            onSelect = { service -> onRouteChange(Route.Service(service)) },
                        )
                    }

                    Route.Alerts -> {
                        AlertsScreen(client, services, alerts, nowMs = nowTick, contentPadding = DesktopContentPadding)
                    }

                    // На десктопе список сервисов всегда в рельсе, отдельного экрана для него нет —
                    // но `route` переживает смену раскладки: можно открыть «Сервисы» в узком окне и
                    // расширить его, не переключая вкладку. Показываем Обзор как разумный дефолт.
                    Route.Services -> {
                        OverviewScreen(
                            client = client,
                            alerts = alerts,
                            nowMs = nowTick,
                            contentPadding = DesktopContentPadding,
                            onSelect = { service -> onRouteChange(Route.Service(service)) },
                        )
                    }

                    is Route.Service -> {
                        // Сервис в маршруте мог устареть между опросами (например, у него
                        // изменился firingAlerts) — берём актуальную версию по id, а если
                        // сервис вдруг пропал из списка, используем последнюю известную,
                        // чтобы экран не схлопывался в пустоту посреди просмотра.
                        val fresh = services.firstOrNull { it.id == current.summary.id } ?: current.summary
                        ServiceScreen(
                            client = client,
                            service = fresh,
                            alerts = alerts,
                            nowMs = nowTick,
                            tab = current.tab,
                            contentPadding = DesktopContentPadding,
                            onTabChange = { newTab -> onRouteChange(current.copy(summary = fresh, tab = newTab)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MobileShell(
    client: MetrikClient,
    services: List<ServiceSummary>,
    alerts: List<AlertView>,
    error: String?,
    firingAlertCount: Int,
    nowTick: () -> Long,
    route: Route,
    onRouteChange: (Route) -> Unit,
) {
    val selectedTab =
        when (route) {
            Route.Overview -> MobileTab.OVERVIEW
            Route.Alerts -> MobileTab.ALERTS
            Route.Services, is Route.Service -> MobileTab.SERVICES
        }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth()) {
            if (error != null) {
                Box(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) { ErrorBanner(error) }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (val current = route) {
                    Route.Overview -> {
                        OverviewScreen(
                            client = client,
                            alerts = alerts,
                            nowMs = nowTick,
                            compact = true,
                            contentPadding = MobileContentPadding,
                            onSelect = { service -> onRouteChange(Route.Service(service)) },
                            onOpenAlerts = { onRouteChange(Route.Alerts) },
                        )
                    }

                    Route.Alerts -> {
                        AlertsScreen(client, services, alerts, compact = true, nowMs = nowTick, contentPadding = MobileContentPadding)
                    }

                    Route.Services -> {
                        MobileServicesListScreen(
                            services = services,
                            selectedServiceId = null,
                            contentPadding = MobileContentPadding,
                            onSelect = { service -> onRouteChange(Route.Service(service)) },
                        )
                    }

                    is Route.Service -> {
                        val fresh = services.firstOrNull { it.id == current.summary.id } ?: current.summary
                        ServiceScreen(
                            client = client,
                            service = fresh,
                            alerts = alerts,
                            nowMs = nowTick,
                            tab = current.tab,
                            compact = true,
                            contentPadding = MobileContentPadding,
                            onBack = { onRouteChange(Route.Services) },
                            onTabChange = { newTab -> onRouteChange(current.copy(summary = fresh, tab = newTab)) },
                        )
                    }
                }
            }
        }

        BottomNavBar(
            selected = selectedTab,
            firingAlertCount = firingAlertCount,
            onSelect = { tab ->
                onRouteChange(
                    when (tab) {
                        MobileTab.OVERVIEW -> Route.Overview
                        MobileTab.ALERTS -> Route.Alerts
                        MobileTab.SERVICES -> Route.Services
                    },
                )
            },
        )
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
