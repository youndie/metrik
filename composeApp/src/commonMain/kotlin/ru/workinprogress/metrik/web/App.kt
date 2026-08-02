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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import io.ktor.client.HttpClient
import org.koin.compose.KoinApplication
import org.koin.compose.viewmodel.koinViewModel
import ru.workinprogress.metrik.web.core.coreModule
import ru.workinprogress.metrik.web.core.data.metrikHttpClient
import ru.workinprogress.metrik.web.feature.alerts.alertsModule
import ru.workinprogress.metrik.web.feature.alerts.ui.AlertsScreen
import ru.workinprogress.metrik.web.feature.service.serviceModule
import ru.workinprogress.metrik.web.feature.service.ui.ServiceScreen
import ru.workinprogress.metrik.web.feature.services.servicesModule
import ru.workinprogress.metrik.web.feature.services.ui.OverviewScreen
import ru.workinprogress.metrik.web.feature.services.ui.ServicesListScreen
import ru.workinprogress.metrik.web.navigation.BrowserBackStackSync
import ru.workinprogress.metrik.web.navigation.Route
import ru.workinprogress.metrik.web.navigation.routeSavedStateConfig
import ru.workinprogress.metrik.web.ui.AppShellUiState
import ru.workinprogress.metrik.web.ui.AppShellViewModel
import ru.workinprogress.metrik.web.ui.BottomNavBar
import ru.workinprogress.metrik.web.ui.MetrikTheme
import ru.workinprogress.metrik.web.ui.MobileTab
import ru.workinprogress.metrik.web.ui.NavRail
import ru.workinprogress.metrik.web.ui.Spacing
import ru.workinprogress.metrik.web.ui.TopRoute

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

/**
 * Дашборд «Metrik Expressive».
 *
 * Десктоп: рельс слева (см. [NavRail]) + контент справа. Мобильная раскладка
 * (`maxWidth < `[MOBILE_MAX_WIDTH]): рельса нет, вместо него нижняя навигация из трёх вкладок
 * (Обзор / Алерты / Сервисы), контент — один столбец. Обе раскладки ходят по одному и тому же
 * back stack'у, поэтому «назад» браузера работает одинаково.
 */
@Composable
fun App(httpClient: HttpClient = metrikHttpClient()) {
    KoinApplication(application = {
        modules(coreModule(httpClient), servicesModule, serviceModule, alertsModule)
    }) {
        MetrikTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceDim) {
                AppShell()
            }
        }
    }
}

@Composable
private fun AppShell(viewModel: AppShellViewModel = koinViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val backStack = rememberNavBackStack(routeSavedStateConfig, Route.Overview)

    BrowserBackStackSync(backStack)

    AppShellContent(uiState = uiState, backStack = backStack)
}

/**
 * Стейтлес-оболочка: знает про состояние и back stack, но ничего не знает про ViewModel.
 */
@Composable
private fun AppShellContent(
    uiState: AppShellUiState,
    backStack: NavBackStack<NavKey>,
) {
    /** Верхнеуровневая вкладка сбрасывает стек: это переключение раздела, а не заход вглубь. */
    fun switchTab(route: Route) {
        if (backStack.singleOrNull() != route) {
            backStack.clear()
            backStack.add(route)
        }
    }

    fun openService(id: Long) {
        if (backStack.lastOrNull() != Route.Service(id)) backStack.add(Route.Service(id))
    }

    val current = backStack.lastOrNull() as? Route

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < MOBILE_MAX_WIDTH
        val contentPadding = if (compact) MobileContentPadding else DesktopContentPadding

        // Паддинга вокруг прокрутки здесь нет намеренно: он уходит внутрь экрана (contentPadding).
        // Снаружи он сужал бы вьюпорт, и контент обрезался бы по внутренней границе — выглядело
        // так, будто экран скроллится внутри рамки.
        val content: @Composable () -> Unit = {
            NavDisplay(
                backStack = backStack,
                onBack = { backStack.removeLastOrNull() },
                entryProvider =
                    entryProvider {
                        entry<Route.Overview> {
                            OverviewScreen(
                                compact = compact,
                                contentPadding = contentPadding,
                                onOpenAlerts = { switchTab(Route.Alerts) },
                                onSelect = { service -> openService(service.id) },
                            )
                        }

                        entry<Route.Alerts> {
                            AlertsScreen(compact = compact, contentPadding = contentPadding)
                        }

                        entry<Route.Services> {
                            // На десктопе список сервисов всегда в рельсе, отдельного экрана для
                            // него нет — но маршрут переживает смену раскладки: можно открыть
                            // «Сервисы» в узком окне и расширить его. Показываем «Обзор» как
                            // разумный дефолт.
                            if (compact) {
                                ServicesListScreen(
                                    services = uiState.services,
                                    contentPadding = contentPadding,
                                    onSelect = { service -> openService(service.id) },
                                )
                            } else {
                                OverviewScreen(
                                    compact = false,
                                    contentPadding = contentPadding,
                                    onOpenAlerts = { switchTab(Route.Alerts) },
                                    onSelect = { service -> openService(service.id) },
                                )
                            }
                        }

                        entry<Route.Service> { route ->
                            ServiceScreen(
                                serviceId = route.id,
                                compact = compact,
                                contentPadding = contentPadding,
                                onBack = { backStack.removeLastOrNull() },
                            )
                        }
                    },
            )
        }

        if (compact) {
            MobileShell(uiState = uiState, current = current, onSelectTab = ::switchTab, content = content)
        } else {
            DesktopShell(
                uiState = uiState,
                current = current,
                onSelectTab = ::switchTab,
                onSelectService = ::openService,
                content = content,
            )
        }
    }
}

@Composable
private fun DesktopShell(
    uiState: AppShellUiState,
    current: Route?,
    onSelectTab: (Route) -> Unit,
    onSelectService: (Long) -> Unit,
    content: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        NavRail(
            services = uiState.services,
            firingAlertCount = uiState.firingAlertCount,
            updatedAgoLabel = uiState.updatedAgoLabel,
            topRoute =
                when (current) {
                    Route.Overview -> TopRoute.OVERVIEW
                    Route.Alerts -> TopRoute.ALERTS
                    else -> null
                },
            selectedServiceId = (current as? Route.Service)?.id,
            onOverview = { onSelectTab(Route.Overview) },
            onAlerts = { onSelectTab(Route.Alerts) },
            onSelectService = { service -> onSelectService(service.id) },
        )

        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(topStart = 28.dp))
                .background(MaterialTheme.colorScheme.surfaceDim),
        ) {
            if (uiState.error != null) {
                Box(Modifier.padding(horizontal = Spacing.xxl, vertical = Spacing.md)) { ErrorBanner(uiState.error) }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        }
    }
}

@Composable
private fun MobileShell(
    uiState: AppShellUiState,
    current: Route?,
    onSelectTab: (Route) -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth()) {
            if (uiState.error != null) {
                Box(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) { ErrorBanner(uiState.error) }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        }

        BottomNavBar(
            selected =
                when (current) {
                    Route.Alerts -> MobileTab.ALERTS
                    Route.Services, is Route.Service -> MobileTab.SERVICES
                    else -> MobileTab.OVERVIEW
                },
            firingAlertCount = uiState.firingAlertCount,
            onSelect = { tab ->
                onSelectTab(
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
