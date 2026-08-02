package ru.workinprogress.metrik.web

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import kotlinx.coroutines.delay
import kotlinx.datetime.TimeZone
import ru.workinprogress.metrik.api.AlertView
import ru.workinprogress.metrik.api.DeployMarker
import ru.workinprogress.metrik.api.RouteRow
import ru.workinprogress.metrik.api.ServiceSummary
import ru.workinprogress.metrik.api.SlowRow
import ru.workinprogress.metrik.api.Step
import ru.workinprogress.metrik.api.SystemPoint
import ru.workinprogress.metrik.api.TimeSeries
import kotlin.math.roundToInt

/**
 * Диапазон периода на экране сервиса.
 *
 * Для «1 ч»/«24 ч» просим минутный шаг: контракт гарантирует минутные окна только за последние
 * 48 часов, оба диапазона укладываются. Для «7 д» просим часовой — иначе сервер всё равно молча
 * отдаст часовой и пометит это в ответе (docs/api/endpoint-query.md, «Правила ответов»), а честно
 * заявленное намерение лучше отражает то, что реально произойдёт.
 */
enum class Range(
    val label: String,
    val ms: Long,
    val step: Step,
) {
    HOUR("1 ч", 60 * 60 * 1000L, Step.MINUTE),
    DAY("24 ч", 24 * 60 * 60 * 1000L, Step.MINUTE),
    WEEK("7 д", 7 * 24 * 60 * 60 * 1000L, Step.HOUR),
}

@Composable
fun RangeSelector(
    selected: Range,
    onSelect: (Range) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Range.entries.forEach { range ->
            val active = range == selected
            Box(
                Modifier
                    .height(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable { onSelect(range) }
                    .padding(horizontal = 22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    range.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val ServiceTabTitles = listOf("Графики", "Маршруты", "Медленные", "Система")

@Composable
private fun ServiceTabBar(
    selected: Int,
    onSelect: (Int) -> Unit,
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
        ServiceTabTitles.forEachIndexed { index, title ->
            val active = index == selected
            Box(
                Modifier
                    .height(if (compact) 38.dp else 42.dp)
                    .clip(RoundedCornerShape(if (compact) 19.dp else 21.dp))
                    .background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(horizontal = if (compact) 18.dp else 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    title,
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
 * (см. [NavRail]) сам заменяет переход к обзору или к другому сервису. На мобильной раскладке
 * рельса нет вовсе, поэтому там читается [onBack] — стрелка возвращает на мобильный список сервисов.
 */
@Composable
fun ServiceScreen(
    client: MetrikClient,
    service: ServiceSummary,
    alerts: List<AlertView>,
    nowMs: () -> Long,
    tab: Int,
    compact: Boolean = false,
    onBack: () -> Unit = {},
    onTabChange: (Int) -> Unit,
) {
    var range by remember(service.id) { mutableStateOf(Range.HOUR) }
    var series by remember { mutableStateOf<TimeSeries?>(null) }
    var routes by remember { mutableStateOf<List<RouteRow>>(emptyList()) }
    var slow by remember { mutableStateOf<List<SlowRow>>(emptyList()) }
    var system by remember { mutableStateOf<List<SystemPoint>>(emptyList()) }
    var loaded by remember(service.id, range) { mutableStateOf(false) }
    val zone = remember { TimeZone.currentSystemDefault() }

    LaunchedEffect(service.id, range) {
        while (true) {
            val to = nowMs()
            val from = to - range.ms
            runCatching {
                series = client.timeSeries(service.id, from, to, range.step)
                routes = client.routes(service.id, from, to)
                // M-85: /slow теперь принимает период, как и остальные вкладки — раньше сервер
                // всегда отдавал последние 24 часа независимо от выбранного диапазона.
                slow = client.slow(service.id, from, to)
                system = client.system(service.id, from, to)
            }
            loaded = true
            delay(REFRESH_MS)
        }
    }

    val firingCount = service.firingAlerts.size

    // Один скролл на весь экран — заголовок и вкладки раньше были прибиты, а прокручивалось только
    // содержимое активной вкладки; теперь всё, включая шапку, едет вместе (то же решение, что и на
    // «Обзоре»/«Алертах» — см. итоговый отчёт задания).
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
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
                    service.name,
                    fontFamily = MetrikMono,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            if (firingCount > 0 || service.instances > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    if (firingCount > 0) FiringCountPill(firingCount)
                    InstancesPill(service.instances)
                }
            }
            RangeSelector(range, { range = it }, Modifier.fillMaxWidth())
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    if (firingCount > 0 || service.instances > 0) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            if (firingCount > 0) FiringCountPill(firingCount)
                            InstancesPill(service.instances)
                        }
                    }
                    Text(
                        service.name,
                        fontFamily = MetrikMono,
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                RangeSelector(range, { range = it })
            }
        }

        ServiceTabBar(tab, onTabChange, compact)

        when (tab) {
            0 -> ChartsTab(series, loaded, range, compact)
            1 -> RoutesTab(routes, loaded, compact)
            2 -> SlowTab(slow, loaded, nowMs, zone, compact)
            else -> SystemTab(system, loaded, compact)
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
            "$count " + pluralRu(count, "алерт", "алерта", "алертов") + " " + pluralRu(count, "горит", "горят", "горят"),
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
            "$count " + pluralRu(count, "инстанс", "инстанса", "инстансов"),
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
        if (loaded) EmptyState("нет данных") else LoadingState("загружаем графики…")
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
                "данные часовые: минутные окна за этот период уже удалены",
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
                        "ЗАПРОСЫ В СЕКУНДУ",
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
                            "rps сейчас · max ${format(maxRps)}",
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
                    "сейчас",
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
                    "P95, МС",
                    points.toChart { it.p95Ms },
                    MaterialTheme.colorScheme.error,
                    "мс · ±20 %",
                    "приблизительно",
                    series.deploys,
                    Modifier.fillMaxWidth(),
                )
                SmallMetricChart(
                    "P50, МС",
                    points.toChart { it.p50Ms },
                    MaterialTheme.colorScheme.primary,
                    "мс · ±20 %",
                    "приблизительно",
                    series.deploys,
                    Modifier.fillMaxWidth(),
                )
                SmallMetricChart(
                    "ДОЛЯ ОШИБОК",
                    points.toChart { it.errorRate * 100 },
                    MaterialTheme.colorScheme.error,
                    "%",
                    "порог 2 %",
                    series.deploys,
                    Modifier.fillMaxWidth(),
                )
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                SmallMetricChart(
                    "P95, МС",
                    points.toChart { it.p95Ms },
                    MaterialTheme.colorScheme.error,
                    "мс · ±20 %",
                    "приблизительно",
                    series.deploys,
                    Modifier.weight(1f),
                )
                SmallMetricChart(
                    "P50, МС",
                    points.toChart { it.p50Ms },
                    MaterialTheme.colorScheme.primary,
                    "мс · ±20 %",
                    "приблизительно",
                    series.deploys,
                    Modifier.weight(1f),
                )
                SmallMetricChart(
                    "ДОЛЯ ОШИБОК",
                    points.toChart { it.errorRate * 100 },
                    MaterialTheme.colorScheme.error,
                    "%",
                    "порог 2 %",
                    series.deploys,
                    Modifier.weight(1f),
                )
            }
        }

        if (partialCount > 0) {
            HonestyChip(
                "разрывы на графиках — окна, от которых пришли не все пакеты",
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
            "$count " + pluralRu(count, "деплой", "деплоя", "деплоев"),
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
            "$count " + pluralRu(count, "разрыв", "разрыва", "разрывов") + " — неполные окна",
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
        if (loaded) EmptyState("нет данных") else LoadingState("загружаем маршруты…")
        return
    }

    val maxCount = routes.maxOf { it.count }.coerceAtLeast(1)
    val maxP95 = routes.maxOf { it.p95Ms }.coerceAtLeast(1.0)

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                HonestyChip(
                    "p50 и p95 приблизительные: ±20 %",
                    MaterialTheme.colorScheme.tertiaryContainer,
                    MaterialTheme.colorScheme.onTertiaryContainer,
                    fontWeight = FontWeight.SemiBold,
                )
                HonestyChip(
                    "Разреза по инстансам здесь нет",
                    MaterialTheme.colorScheme.surfaceContainerLow,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                HonestyChip(
                    "p50 и p95 приблизительные: ±20 % — ширина бакета гистограммы",
                    MaterialTheme.colorScheme.tertiaryContainer,
                    MaterialTheme.colorScheme.onTertiaryContainer,
                    fontWeight = FontWeight.SemiBold,
                )
                HonestyChip(
                    "Разреза по инстансам здесь нет — данные так не хранятся",
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
        RouteHeaderCell("МАРШРУТ", 2.6f)
        RouteHeaderCell("СТАТУС", 0.7f)
        RouteHeaderCell("КОЛ-ВО", 1.6f)
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
    nowMs: () -> Long,
    zone: TimeZone,
    compact: Boolean = false,
) {
    if (slow.isEmpty()) {
        if (loaded) EmptyState("нет данных") else LoadingState("загружаем медленные запросы…")
        return
    }

    val maxDuration = slow.maxOf { it.durationMs }.coerceAtLeast(1)
    val now = nowMs()

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        HonestyChip(
            "Это отдельные запросы, а не перцентиль: сэмплируется хвост, поэтому список показывает поводы, а не долю.",
            MaterialTheme.colorScheme.surfaceContainerLow,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            slow.forEach { row -> SlowRowItem(row, maxDuration, now, zone, compact) }
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
                "${row.durationMs} мс",
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
        if (loaded) EmptyState("нет данных") else LoadingState("загружаем системные метрики…")
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
    // У нативного процесса нет верхней границы heap в смысле JVM — то, что мы видим, это RSS
    // всего процесса. Без объявленного максимума нельзя честно посчитать долю от лимита, поэтому
    // для native полосы заполнения просто нет — только цифра и пояснение.
    val native = last.heapMaxBytes == null
    val usedMb = last.heapUsedBytes / 1024 / 1024
    val ratio = last.heapMaxBytes?.let { max -> (last.heapUsedBytes.toFloat() / max).coerceIn(0f, 1f) }
    val hot = ratio != null && ratio > 0.85f
    val memColor = if (hot) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val memLabel = if (native) "RSS процесса" else "heap / ${last.heapMaxBytes!! / 1024 / 1024} МБ"
    val kindLabel = if (native) "Kotlin/Native" else "JVM"
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
                    Text("$usedMb МБ", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = memColor)
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
                        "${(ratio * 100).roundToInt()} % от лимита",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = MetrikMono,
                        color = MaterialTheme.colorScheme.outline,
                    )
                } else {
                    Text(
                        "у нативного процесса нет максимума heap — это RSS",
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
            StatTile("тредов", last.threads.toString(), Modifier.weight(1f))
            StatTile(
                "GC",
                if (gcMissing) "нет данных" else last.gcCollections.toString(),
                Modifier.weight(1.4f),
                background = if (gcMissing) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                foreground = if (gcMissing) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurface,
                labelColor = if (gcMissing) MaterialTheme.colorScheme.onTertiaryContainer else MetrikExtra.dim,
            )
        }
    }
}
