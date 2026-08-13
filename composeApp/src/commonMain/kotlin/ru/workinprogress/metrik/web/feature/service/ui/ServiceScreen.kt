package ru.workinprogress.metrik.web.feature.service.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.datetime.TimeZone
import org.koin.compose.viewmodel.koinViewModel
import ru.workinprogress.metrik.api.AlertView
import ru.workinprogress.metrik.api.DeployMarker
import ru.workinprogress.metrik.api.RouteRow
import ru.workinprogress.metrik.api.ServiceRuntime
import ru.workinprogress.metrik.api.ServiceSummary
import ru.workinprogress.metrik.api.SlowRow
import ru.workinprogress.metrik.api.Step
import ru.workinprogress.metrik.api.SystemPoint
import ru.workinprogress.metrik.api.TimeSeries
import ru.workinprogress.metrik.api.serviceRuntime
import ru.workinprogress.metrik.web.core.domain.Range
import ru.workinprogress.metrik.web.ui.ChartPoint
import ru.workinprogress.metrik.web.ui.EmptyState
import ru.workinprogress.metrik.web.ui.HonestyChip
import ru.workinprogress.metrik.web.ui.LineChart
import ru.workinprogress.metrik.web.ui.LoadingState
import ru.workinprogress.metrik.web.ui.MetrikExtra
import ru.workinprogress.metrik.web.ui.MetrikMono
import ru.workinprogress.metrik.web.ui.RangeSelector
import ru.workinprogress.metrik.web.ui.Spacing
import ru.workinprogress.metrik.web.ui.Sparkline
import ru.workinprogress.metrik.web.ui.StatTile
import ru.workinprogress.metrik.web.ui.absoluteAgo
import ru.workinprogress.metrik.web.ui.format
import ru.workinprogress.metrik.web.ui.plural
import ru.workinprogress.metrik.web.ui.statusColor
import ru.workinprogress.metrik.web.ui.toChart
import kotlin.math.roundToInt

@Composable
private fun ServiceTabBar(
    selected: ServiceTab,
    onSelect: (ServiceTab) -> Unit,
    compact: Boolean = false,
) {
    // На мобильной ширине четыре вкладки в полный размер не помещаются (макет показывает урезанный
    // ряд с overflow:hidden) — вместо того чтобы тихо прятать «Система», делаем ряд горизонтально
    // прокручиваемым: все вкладки остаются достижимы, просто не все видны одновременно.
    val rowModifier = if (compact) Modifier.horizontalScroll(rememberScrollState()) else Modifier
    Row(
        rowModifier
            .clip(RoundedCornerShape(if (compact) 19.dp else 26.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(if (compact) 4.dp else 5.dp),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
    ) {
        ServiceTab.entries.forEach { tab ->
            val active = tab == selected
            Box(
                Modifier
                    .height(if (compact) 38.dp else 42.dp)
                    .clip(RoundedCornerShape(if (compact) 19.dp else 21.dp))
                    .background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .clickable { onSelect(tab) }
                    .padding(horizontal = if (compact) 18.dp else 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    tab.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    color =
                        if (active) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
        }
    }
}

/**
 * Экран одного сервиса: четыре вкладки поверх общего заголовка. На десктопе «назад» нет — рельс
 * сам заменяет переход к обзору или к другому сервису. На мобильной раскладке рельса нет вовсе,
 * поэтому там читается [onBack] — стрелка возвращает на список сервисов.
 */
@Composable
fun ServiceScreen(
    serviceId: Long,
    viewModel: ServiceViewModel = koinViewModel(),
    compact: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onBack: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Экран сервиса один, а сервис на нём меняется: из рельса переходят вбок, с одного на другой.
    LaunchedEffect(serviceId) { viewModel.onAction(ServiceUiAction.Open(serviceId)) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                // Сервиса больше нет — показывать его экран нечестно, уходим назад.
                ServiceUiEvent.Deleted -> onBack()
            }
        }
    }

    ServiceContent(
        uiState = uiState,
        onAction = viewModel::onAction,
        compact = compact,
        contentPadding = contentPadding,
        onBack = onBack,
    )
}

/** Стейтлес — всё через [uiState]/[onAction], про ViewModel ничего не знает. */
@Composable
fun ServiceContent(
    uiState: ServiceUiState,
    onAction: (ServiceUiAction) -> Unit,
    compact: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onBack: () -> Unit = {},
) {
    val zone = remember { TimeZone.currentSystemDefault() }
    val service = uiState.service
    val firingCount = service?.firingAlerts?.size ?: 0
    val instances = service?.instances ?: 0
    val onRange: (Range) -> Unit = { range -> onAction(ServiceUiAction.SelectRange(range)) }

    // Один скролл на весь экран: заголовок и вкладки едут вместе с содержимым вкладки, а не
    // остаются прибитыми над отдельно прокручиваемой областью.
    Column(
        // Паддинг применяется ПОСЛЕ verticalScroll и потому едет вместе с контентом. Если
        // повесить его снаружи (на контейнер шелла), вьюпорт сужается, и контент режется по
        // внутренней границе — выглядит так, будто он скроллится внутри рамки.
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        if (compact) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("←", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    service?.name.orEmpty(),
                    fontFamily = MetrikMono,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            if (firingCount > 0 || instances > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    if (firingCount > 0) FiringCountPill(firingCount)
                    InstancesPill(instances)
                }
            }
            RangeSelector(uiState.range, onRange, Modifier.fillMaxWidth())
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                // weight(1f) обязателен: без него длинное имя сервиса занимает всю ширину по
                // своему размеру и выдавливает правую колонку за край окна.
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    if (firingCount > 0 || instances > 0) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            if (firingCount > 0) FiringCountPill(firingCount)
                            InstancesPill(instances)
                        }
                    }
                    Text(
                        service?.name.orEmpty(),
                        fontFamily = MetrikMono,
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                RangeSelector(uiState.range, onRange)
            }
            // Отдельной строкой во всю ширину, а не в шапке: подтверждение удаления — это текст
            // плюс две кнопки, и в тесной шапке оно выдавливало кнопки за край окна.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                RemoveFromMonitoring(uiState, onAction)
            }
        }

        ServiceTabBar(uiState.tab, { tab -> onAction(ServiceUiAction.SelectTab(tab)) }, compact)

        when (uiState.tab) {
            ServiceTab.CHARTS -> ChartsTab(uiState.series, uiState.loaded, uiState.range, compact)
            ServiceTab.ROUTES -> RoutesTab(uiState.routes, uiState.loaded, compact)
            ServiceTab.SLOW -> SlowTab(uiState.slow, uiState.loaded, uiState.nowMs, zone, compact)
            ServiceTab.SYSTEM -> SystemTab(uiState.system, uiState.loaded, compact)
        }
    }
}

/**
 * Убрать сервис из наблюдения.
 *
 * Нужно, когда сервис увели или переименовали: старая запись честно молчит и потому вечно горит
 * правилом `absent`. Заглушение здесь не подходит — оно прячет живую проблему, а тут проблемы нет.
 *
 * Кнопка намеренно тихая и в два шага: операция необратима, вместе с сервисом уезжает вся его
 * история. Одного случайного клика для этого мало.
 */
@Composable
private fun RemoveFromMonitoring(
    uiState: ServiceUiState,
    onAction: (ServiceUiAction) -> Unit,
) {
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (uiState.deleteRequested) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    "Delete it along with all its history?",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(
                    Modifier
                        .height(32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.error)
                        .clickable(enabled = !uiState.deleting) { onAction(ServiceUiAction.ConfirmDelete) }
                        .padding(horizontal = Spacing.md),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (uiState.deleting) "Deleting…" else "Delete",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onError,
                    )
                }
                Box(
                    Modifier
                        .height(32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(enabled = !uiState.deleting) { onAction(ServiceUiAction.CancelDelete) }
                        .padding(horizontal = Spacing.md),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Cancel", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            Box(
                Modifier
                    .height(32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onAction(ServiceUiAction.RequestDelete) }
                    .padding(horizontal = Spacing.md),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Remove from monitoring",
                    style = MaterialTheme.typography.labelMedium,
                    color = MetrikExtra.dim,
                )
            }
        }

        if (uiState.deleteError != null) {
            Text(uiState.deleteError, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun FiringCountPill(count: Int) {
    Row(
        Modifier
            .height(26.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onErrorContainer))
        Text(
            "$count " + plural(count, "alert") + " firing",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun InstancesPill(count: Int) {
    Box(
        Modifier
            .height(26.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "$count " + plural(count, "instance"),
            fontFamily = MetrikMono,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

// ─────────────────────────────── Графики ───────────────────────────────

@Composable
private fun ChartsTab(
    series: TimeSeries?,
    loaded: Boolean,
    range: Range,
    compact: Boolean = false,
) {
    if (series == null) {
        if (loaded) EmptyState("no data") else LoadingState("loading charts…")
        return
    }

    val points = series.points
    val rpsPoints = points.toChart { it.requestsPerSecond }
    val lastRps = points.lastOrNull()?.takeUnless { it.partial }?.requestsPerSecond
    val maxRps = points.filterNot { it.partial }.maxOfOrNull { it.requestsPerSecond } ?: 0.0
    val partialCount = points.count { it.partial }
    val heroHeight = if (compact) 128.dp else 236.dp

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        if (series.step != Step.MINUTE) {
            HonestyChip(
                "hourly data: the minute windows for this period have been deleted",
                MaterialTheme.colorScheme.tertiaryContainer,
                MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(36.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(horizontal = Spacing.xl, vertical = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                    Text(
                        "REQUESTS PER SECOND",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = MetrikMono,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text(
                            lastRps?.let { format(it) } ?: "—",
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "rps now · max ${format(maxRps)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MetrikExtra.dim,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    if (series.deploys.isNotEmpty()) DeployCountChip(series.deploys.size)
                    if (partialCount > 0) GapCountChip(partialCount)
                }
            }

            Box(Modifier.fillMaxWidth().height(heroHeight)) {
                LineChart(
                    title = "",
                    points = rpsPoints,
                    deploys = series.deploys,
                    color = MaterialTheme.colorScheme.primary,
                    showGrid = true,
                    showHeader = false,
                    height = heroHeight,
                )
                DeployLabelsOverlay(rpsPoints, series.deploys, Modifier.matchParentSize())
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "− ${range.label}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = MetrikMono,
                    color = MaterialTheme.colorScheme.outline,
                )
                Text(
                    "now",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = MetrikMono,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }

        // На десктопе — три колонки в ряд (по макету); на мобильной ширине они не влезают
        // читаемо, поэтому вместо weight(1f) в Row складываем их в одну колонку на всю ширину.
        if (compact) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                SmallMetricChart(
                    "P95, MS",
                    points.toChart { it.p95Ms },
                    MaterialTheme.colorScheme.error,
                    "ms · ±20%",
                    "approximate",
                    series.deploys,
                    Modifier.fillMaxWidth(),
                )
                SmallMetricChart(
                    "P50, MS",
                    points.toChart { it.p50Ms },
                    MaterialTheme.colorScheme.primary,
                    "ms · ±20%",
                    "approximate",
                    series.deploys,
                    Modifier.fillMaxWidth(),
                )
                SmallMetricChart(
                    "ERROR RATE",
                    points.toChart { it.errorRate * 100 },
                    MaterialTheme.colorScheme.error,
                    "%",
                    "threshold 2%",
                    series.deploys,
                    Modifier.fillMaxWidth(),
                )
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                SmallMetricChart(
                    "P95, MS",
                    points.toChart { it.p95Ms },
                    MaterialTheme.colorScheme.error,
                    "ms · ±20%",
                    "approximate",
                    series.deploys,
                    Modifier.weight(1f),
                )
                SmallMetricChart(
                    "P50, MS",
                    points.toChart { it.p50Ms },
                    MaterialTheme.colorScheme.primary,
                    "ms · ±20%",
                    "approximate",
                    series.deploys,
                    Modifier.weight(1f),
                )
                SmallMetricChart(
                    "ERROR RATE",
                    points.toChart { it.errorRate * 100 },
                    MaterialTheme.colorScheme.error,
                    "%",
                    "threshold 2%",
                    series.deploys,
                    Modifier.weight(1f),
                )
            }
        }

        if (partialCount > 0) {
            HonestyChip(
                "gaps on the charts are windows that did not receive all their packets",
                MaterialTheme.colorScheme.surfaceContainerLow,
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SmallMetricChart(
    title: String,
    points: List<ChartPoint>,
    color: Color,
    unit: String,
    badge: String,
    deploys: List<DeployMarker>,
    modifier: Modifier = Modifier,
) {
    val last = points.lastOrNull()?.value
    Column(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = MetrikMono,
                color = MaterialTheme.colorScheme.outline,
            )
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(badge, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                last?.let { format(it) } ?: "—",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = color,
            )
            Text(unit, style = MaterialTheme.typography.bodySmall, color = MetrikExtra.dim)
        }
        LineChart(title = "", points = points, deploys = deploys, color = color, showHeader = false, height = 96.dp)
    }
}

@Composable
private fun DeployCountChip(count: Int) {
    Row(
        Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Box(Modifier.width(2.dp).height(12.dp).background(MaterialTheme.colorScheme.onTertiaryContainer))
        Text(
            "$count " + plural(count, "deploy"),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

@Composable
private fun GapCountChip(count: Int) {
    Row(
        Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Box(Modifier.width(14.dp).height(2.dp).background(MaterialTheme.colorScheme.onSurfaceVariant))
        Text(
            "$count " + plural(count, "gap") + " — incomplete windows",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Подписи деплоев над графиком, на своих X-координатах — как в макете (`hero.deploys`), а не
 * отдельным списком под ним: положение метки относительно линии — часть смысла «здесь выкатили».
 */
@Composable
private fun DeployLabelsOverlay(
    points: List<ChartPoint>,
    deploys: List<DeployMarker>,
    modifier: Modifier = Modifier,
) {
    if (points.isEmpty() || deploys.isEmpty()) return
    val minAt = points.minOf { it.at }
    val maxAt = points.maxOf { it.at }
    val span = (maxAt - minAt).coerceAtLeast(1)

    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        deploys.forEach { deploy ->
            val fraction = ((deploy.at - minAt).toFloat() / span).coerceIn(0f, 1f)
            val xDp: Dp = with(density) { (maxWidth.toPx() * fraction).toDp() }
            Box(Modifier.offset(x = xDp - 40.dp)) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.tertiaryContainer)
                        .padding(horizontal = 11.dp, vertical = 4.dp),
                ) {
                    Text(
                        deploy.release,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = MetrikMono,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

// ─────────────────────────────── Маршруты ───────────────────────────────

@Composable
private fun RoutesTab(
    routes: List<RouteRow>,
    loaded: Boolean,
    compact: Boolean = false,
) {
    if (routes.isEmpty()) {
        if (loaded) EmptyState("no data") else LoadingState("loading routes…")
        return
    }

    val maxCount = routes.maxOf { it.count }.coerceAtLeast(1)
    val maxP95 = routes.maxOf { it.p95Ms }.coerceAtLeast(1.0)

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                HonestyChip(
                    "p50 and p95 are approximate: ±20%",
                    MaterialTheme.colorScheme.tertiaryContainer,
                    MaterialTheme.colorScheme.onTertiaryContainer,
                    fontWeight = FontWeight.SemiBold,
                )
                HonestyChip(
                    "No per-instance breakdown here",
                    MaterialTheme.colorScheme.surfaceContainerLow,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                HonestyChip(
                    "p50 and p95 are approximate: ±20% — the width of a histogram bucket",
                    MaterialTheme.colorScheme.tertiaryContainer,
                    MaterialTheme.colorScheme.onTertiaryContainer,
                    fontWeight = FontWeight.SemiBold,
                )
                HonestyChip(
                    "No per-instance breakdown here — the data is not stored that way",
                    MaterialTheme.colorScheme.surfaceContainerLow,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (compact) {
            // Шесть колонок таблицы на 390dp нечитаемы (M-89) — карточка на маршрут: метод+путь
            // первой строкой, статус и цифры второй, бары длительности как на десктопе.
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                routes.forEach { row -> RouteCardItem(row, maxCount, maxP95) }
            }
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(32.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
            ) {
                RouteHeaderRow()
                routes.forEachIndexed { index, row ->
                    RouteRowItem(row, maxCount, maxP95, routeRowShape(index, routes.size))
                }
            }
        }
    }
}

/** Карточка маршрута для узкого экрана (M-89) — та же честность, что и в таблице, другая раскладка. */
@Composable
private fun RouteCardItem(
    row: RouteRow,
    maxCount: Long,
    maxP95: Double,
) {
    val bad = row.status >= 500
    val warn = row.status in 400..499
    val bg = if (bad) MetrikExtra.criticalRowBackground else MaterialTheme.colorScheme.surfaceContainerLow
    val fg = if (bad) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
    val statusBg =
        when {
            bad -> MaterialTheme.colorScheme.errorContainer
            warn -> MaterialTheme.colorScheme.tertiaryContainer
            else -> MetrikExtra.healthyContainer
        }
    val statusFg =
        when {
            bad -> MaterialTheme.colorScheme.onErrorContainer
            warn -> MaterialTheme.colorScheme.onTertiaryContainer
            else -> MetrikExtra.onHealthyContainer
        }
    val methodBg = if (row.method == "POST") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
    val methodFg =
        if (row.method == "POST") {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        }
    val slow = row.p95Ms >= 400
    val barColor = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val p95Color = if (slow) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val p95Fg = if (slow) MaterialTheme.colorScheme.error else fg
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHigh

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md + Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        // Первая строка — метод и маршрут, как требует задание.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Box(
                Modifier.clip(RoundedCornerShape(8.dp)).background(methodBg).padding(horizontal = 9.dp, vertical = 3.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    row.method,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = MetrikMono,
                    fontWeight = FontWeight.Bold,
                    color = methodFg,
                )
            }
            Text(
                row.route,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = MetrikMono,
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        // Вторая строка — статус и цифры (кол-во, p50, max), как требует задание.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Box(Modifier.clip(RoundedCornerShape(9.dp)).background(statusBg).padding(horizontal = 10.dp, vertical = 3.dp)) {
                Text(
                    row.status.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = MetrikMono,
                    fontWeight = FontWeight.Bold,
                    color = statusFg,
                )
            }
            Text(
                "${row.count} · p50 ${format(row.p50Ms)} · max ${row.maxMs}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = MetrikMono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        // Бары длительности — те же, что на десктопе: количество запросов и p95.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Box(
                Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(trackColor),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth((row.count.toFloat() / maxCount).coerceIn(0.03f, 1f))
                        .clip(RoundedCornerShape(3.dp))
                        .background(barColor),
                )
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(trackColor),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth((row.p95Ms / maxP95).toFloat().coerceIn(0.03f, 1f))
                        .clip(RoundedCornerShape(3.dp))
                        .background(p95Color),
                )
            }
            Text(
                "p95 ${format(row.p95Ms)}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = MetrikMono,
                fontWeight = FontWeight.Bold,
                color = p95Fg,
            )
        }
    }
}

private fun routeRowShape(
    index: Int,
    size: Int,
): Shape =
    when {
        size == 1 -> RoundedCornerShape(16.dp)
        index == 0 -> RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp, bottomStart = 12.dp, bottomEnd = 12.dp)
        index == size - 1 -> RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 26.dp, bottomEnd = 26.dp)
        else -> RoundedCornerShape(12.dp)
    }

@Composable
private fun RouteHeaderRow() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.lg + Spacing.sm, vertical = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xl - Spacing.xs),
    ) {
        RouteHeaderCell("ROUTE", 2.6f)
        RouteHeaderCell("STATUS", 0.7f)
        RouteHeaderCell("COUNT", 1.6f)
        RouteHeaderCell("P50", 0.7f)
        RouteHeaderCell("P95", 1.8f)
        RouteHeaderCell("MAX", 0.7f)
    }
}

@Composable
private fun RowScope.RouteHeaderCell(
    text: String,
    weight: Float,
) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = MetrikMono,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.weight(weight),
    )
}

@Composable
private fun RouteRowItem(
    row: RouteRow,
    maxCount: Long,
    maxP95: Double,
    shape: Shape,
) {
    val bad = row.status >= 500
    val warn = row.status in 400..499
    val bg = if (bad) MetrikExtra.criticalRowBackground else MaterialTheme.colorScheme.surfaceContainerLow
    val fg = if (bad) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
    val statusBg =
        when {
            bad -> MaterialTheme.colorScheme.errorContainer
            warn -> MaterialTheme.colorScheme.tertiaryContainer
            else -> MetrikExtra.healthyContainer
        }
    val statusFg =
        when {
            bad -> MaterialTheme.colorScheme.onErrorContainer
            warn -> MaterialTheme.colorScheme.onTertiaryContainer
            else -> MetrikExtra.onHealthyContainer
        }
    val methodBg = if (row.method == "POST") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
    val methodFg =
        if (row.method == "POST") {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        }
    val slow = row.p95Ms >= 400
    val barColor = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val p95Color = if (slow) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val p95Fg = if (slow) MaterialTheme.colorScheme.error else fg
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHigh

    Row(
        Modifier
            .fillMaxWidth()
            .clip(
                shape,
            ).background(bg)
            .padding(horizontal = Spacing.lg + Spacing.sm, vertical = Spacing.lg - Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xl - Spacing.xs),
    ) {
        Row(
            Modifier.weight(2.6f),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.clip(RoundedCornerShape(8.dp)).background(methodBg).padding(horizontal = 9.dp, vertical = 3.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    row.method,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = MetrikMono,
                    fontWeight = FontWeight.Bold,
                    color = methodFg,
                )
            }
            Text(row.route, style = MaterialTheme.typography.bodySmall, fontFamily = MetrikMono, color = fg)
        }
        Box(Modifier.weight(0.7f)) {
            Box(
                Modifier.clip(RoundedCornerShape(9.dp)).background(statusBg).padding(horizontal = 10.dp, vertical = 3.dp),
            ) {
                Text(
                    row.status.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = MetrikMono,
                    fontWeight = FontWeight.Bold,
                    color = statusFg,
                )
            }
        }
        Row(
            Modifier.weight(1.6f),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(trackColor),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth((row.count.toFloat() / maxCount).coerceIn(0.03f, 1f))
                        .clip(RoundedCornerShape(3.dp))
                        .background(barColor),
                )
            }
            Text(row.count.toString(), style = MaterialTheme.typography.bodySmall, fontFamily = MetrikMono, color = fg)
        }
        Text(
            format(row.p50Ms),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = MetrikMono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.7f),
        )
        Row(
            Modifier.weight(1.8f),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(120.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(trackColor),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth((row.p95Ms / maxP95).toFloat().coerceIn(0.03f, 1f))
                        .clip(RoundedCornerShape(3.dp))
                        .background(p95Color),
                )
            }
            Text(
                format(row.p95Ms),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = MetrikMono,
                fontWeight = FontWeight.Bold,
                color = p95Fg,
            )
        }
        Text(
            row.maxMs.toString(),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = MetrikMono,
            color = MetrikExtra.dim,
            modifier = Modifier.weight(0.7f),
        )
    }
}

// ─────────────────────────────── Медленные ───────────────────────────────

@Composable
private fun SlowTab(
    slow: List<SlowRow>,
    loaded: Boolean,
    nowMs: Long,
    zone: TimeZone,
    compact: Boolean = false,
) {
    if (slow.isEmpty()) {
        if (loaded) EmptyState("no data") else LoadingState("loading slow requests…")
        return
    }

    val maxDuration = slow.maxOf { it.durationMs }.coerceAtLeast(1)

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        HonestyChip(
            "These are individual requests, not a percentile: the tail is sampled, so the list shows reasons, not a share.",
            MaterialTheme.colorScheme.surfaceContainerLow,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            slow.forEach { row -> SlowRowItem(row, maxDuration, nowMs, zone, compact) }
        }
    }
}

@Composable
private fun SlowRowItem(
    row: SlowRow,
    maxDuration: Int,
    nowMs: Long,
    zone: TimeZone,
    compact: Boolean = false,
) {
    val hot = row.durationMs >= 3000
    val warm = row.durationMs >= 1500
    val barColor =
        when {
            hot -> MaterialTheme.colorScheme.error
            warm -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.primary
        }
    val statusBg =
        when {
            row.status >= 500 -> MaterialTheme.colorScheme.errorContainer
            row.status >= 400 -> MaterialTheme.colorScheme.tertiaryContainer
            else -> MetrikExtra.healthyContainer
        }
    val statusFg =
        when {
            row.status >= 500 -> MaterialTheme.colorScheme.onErrorContainer
            row.status >= 400 -> MaterialTheme.colorScheme.onTertiaryContainer
            else -> MetrikExtra.onHealthyContainer
        }
    val methodBg = if (row.method == "POST") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
    val methodFg =
        if (row.method == "POST") {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        }

    // Абсолютное время (M-83): период на вкладке теперь выбираемый (M-85), а не всегда «24 ч» —
    // «N назад» на многодневном диапазоне честности не добавляет, точный момент — добавляет.
    val at = absoluteAgo(nowMs, row.at, zone)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = Spacing.lg + Spacing.xs, vertical = Spacing.md + Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        if (compact) {
            // На узкой ширине метод+маршрут и статус+время не помещаются в одну строку без
            // обрезания — разносим на две, порядок элементов внутри строк тот же, что на десктопе.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Box(Modifier.clip(RoundedCornerShape(8.dp)).background(methodBg).padding(horizontal = 9.dp, vertical = 3.dp)) {
                    Text(
                        row.method,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = MetrikMono,
                        fontWeight = FontWeight.Bold,
                        color = methodFg,
                    )
                }
                Text(
                    row.route,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = MetrikMono,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Box(Modifier.clip(RoundedCornerShape(8.dp)).background(statusBg).padding(horizontal = 9.dp, vertical = 3.dp)) {
                    Text(
                        row.status.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = MetrikMono,
                        fontWeight = FontWeight.Bold,
                        color = statusFg,
                    )
                }
                Text(
                    at,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = MetrikMono,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Box(Modifier.clip(RoundedCornerShape(8.dp)).background(methodBg).padding(horizontal = 9.dp, vertical = 3.dp)) {
                    Text(
                        row.method,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = MetrikMono,
                        fontWeight = FontWeight.Bold,
                        color = methodFg,
                    )
                }
                Text(
                    row.route,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = MetrikMono,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Box(Modifier.clip(RoundedCornerShape(8.dp)).background(statusBg).padding(horizontal = 9.dp, vertical = 3.dp)) {
                    Text(
                        row.status.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = MetrikMono,
                        fontWeight = FontWeight.Bold,
                        color = statusFg,
                    )
                }
                Text(
                    at,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = MetrikMono,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth((row.durationMs.toFloat() / maxDuration).coerceIn(0.02f, 1f))
                        .clip(RoundedCornerShape(4.dp))
                        .background(barColor),
                )
            }
            Text(
                "${row.durationMs} ms",
                style = MaterialTheme.typography.titleSmall,
                fontFamily = MetrikMono,
                fontWeight = FontWeight.Bold,
                color = if (hot) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

// ─────────────────────────────── Система ───────────────────────────────

@Composable
private fun SystemTab(
    system: List<SystemPoint>,
    loaded: Boolean,
    compact: Boolean = false,
) {
    if (system.isEmpty()) {
        if (loaded) EmptyState("no data") else LoadingState("loading system metrics…")
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        system.groupBy { it.instance }.forEach { (instance, points) ->
            InstanceCard(instance, points, compact)
        }
    }
}

@Composable
private fun InstanceCard(
    instance: String,
    points: List<SystemPoint>,
    compact: Boolean = false,
) {
    val last = points.last()
    // Платформу берём из того, что прислал агент, и не выводим из данных. Раньше нативным считался
    // тот, у кого нет heapMaxBytes, — но в контейнере нативный агент кладёт туда лимит cgroup, и
    // все нативные сервисы подписывались как JVM, а RSS выдавался за heap.
    val runtime = last.serviceRuntime
    val native = runtime == ServiceRuntime.NATIVE
    val jvm = runtime == ServiceRuntime.JVM
    val usedMb = last.heapUsedBytes / 1024 / 1024
    // Долю от лимита рисуем только для JVM: у нативного процесса heapMaxBytes — это лимит cgroup,
    // а доля RSS от лимита контейнера и «сколько занято в heap» отвечают на разные вопросы.
    val ratio = if (jvm) last.heapMaxBytes?.let { max -> (last.heapUsedBytes.toFloat() / max).coerceIn(0f, 1f) } else null
    val hot = ratio != null && ratio > 0.85f
    val memColor = if (hot) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val memLabel =
        when {
            jvm && last.heapMaxBytes != null -> "heap / ${last.heapMaxBytes!! / 1024 / 1024} MB"

            native -> "process RSS"

            // Агент платформу не прислал — назвать величину нечем, и выдумывать её нельзя.
            else -> "process memory"
        }
    val kindLabel =
        when (runtime) {
            ServiceRuntime.NATIVE -> "Kotlin/Native"
            ServiceRuntime.JVM -> "JVM"
            ServiceRuntime.UNKNOWN -> "runtime unknown"
        }
    val kindBg = if (native) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer
    val kindFg = if (native) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
    val gcMissing = last.gcCollections == null

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = Spacing.xl - Spacing.xs, vertical = Spacing.xl - Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                instance,
                style = MaterialTheme.typography.titleSmall,
                fontFamily = MetrikMono,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Box(Modifier.clip(RoundedCornerShape(9.dp)).background(kindBg).padding(horizontal = 10.dp, vertical = 3.dp)) {
                Text(kindLabel, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = kindFg)
            }
        }

        val memInfo: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text("$usedMb MB", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = memColor)
                    Text(memLabel, style = MaterialTheme.typography.labelSmall, fontFamily = MetrikMono, color = MetrikExtra.dim)
                }
                if (ratio != null) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(
                                10.dp,
                            ).clip(RoundedCornerShape(5.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer),
                    ) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(ratio)
                                .clip(RoundedCornerShape(5.dp))
                                .background(memColor),
                        )
                    }
                    Text(
                        "${(ratio * 100).roundToInt()}% of the limit",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = MetrikMono,
                        color = MaterialTheme.colorScheme.outline,
                    )
                } else {
                    Text(
                        "a native process has no heap maximum — this is RSS",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = MetrikMono,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
        val memSparkline = points.map { ChartPoint(it.at, it.heapUsedBytes / 1024.0 / 1024.0) }

        if (compact) {
            // Фиксированная ширина спарклайна (200dp) рядом с цифрами — десктопная раскладка,
            // на 390dp она либо переполняет строку, либо сжимает цифры до нечитаемости (M-89):
            // на узком экране спарклайн на всю ширину под цифрами, а не сбоку от них.
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                memInfo()
                Sparkline(
                    memSparkline,
                    memColor,
                    Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(12.dp)),
                )
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { memInfo() }
                Sparkline(
                    memSparkline,
                    memColor,
                    Modifier.width(200.dp).height(64.dp).clip(RoundedCornerShape(12.dp)),
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            StatTile("CPU", "${last.cpuPermille / 10.0} %", Modifier.weight(1f))
            StatTile("threads", last.threads.toString(), Modifier.weight(1f))
            StatTile(
                "GC",
                if (gcMissing) "no data" else last.gcCollections.toString(),
                Modifier.weight(1.4f),
                background = if (gcMissing) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                foreground = if (gcMissing) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurface,
                labelColor = if (gcMissing) MaterialTheme.colorScheme.onTertiaryContainer else MetrikExtra.dim,
            )
        }
    }
}
