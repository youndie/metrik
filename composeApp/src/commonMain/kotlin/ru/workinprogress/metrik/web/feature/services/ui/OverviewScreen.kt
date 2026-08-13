package ru.workinprogress.metrik.web.feature.services.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import ru.workinprogress.metrik.api.AlertView
import ru.workinprogress.metrik.api.ServiceSummary
import ru.workinprogress.metrik.web.core.domain.Range
import ru.workinprogress.metrik.web.ui.ChartPoint
import ru.workinprogress.metrik.web.ui.EmptyState
import ru.workinprogress.metrik.web.ui.LoadingState
import ru.workinprogress.metrik.web.ui.MetrikExtra
import ru.workinprogress.metrik.web.ui.MetrikMono
import ru.workinprogress.metrik.web.ui.RangeSelector
import ru.workinprogress.metrik.web.ui.Spacing
import ru.workinprogress.metrik.web.ui.Sparkline
import ru.workinprogress.metrik.web.ui.format
import ru.workinprogress.metrik.web.ui.plural

/**
 * Обзор: hero горящих алертов (если есть) и сетка карточек сервисов.
 *
 * Экран сам себе хозяин по данным (см. [OverviewViewModel]): переключатель диапазона реально
 * перезапрашивает список сервисов и спарклайны за выбранный период.
 */
@Composable
fun OverviewScreen(
    viewModel: OverviewViewModel = koinViewModel(),
    compact: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onOpenAlerts: () -> Unit = {},
    onSelect: (ServiceSummary) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    OverviewContent(
        uiState = uiState,
        onAction = viewModel::onAction,
        compact = compact,
        contentPadding = contentPadding,
        onOpenAlerts = onOpenAlerts,
        onSelect = onSelect,
    )
}

/** Стейтлес — всё через [uiState]/[onAction], про ViewModel ничего не знает. */
@Composable
fun OverviewContent(
    uiState: OverviewUiState,
    onAction: (OverviewUiAction) -> Unit,
    compact: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onOpenAlerts: () -> Unit = {},
    onSelect: (ServiceSummary) -> Unit,
) {
    val services = uiState.services
    val firing = uiState.firingAlerts

    Column(
        // Паддинг применяется ПОСЛЕ verticalScroll и потому едет вместе с контентом. Если
        // повесить его снаружи (на контейнер шелла), вьюпорт сужается, и контент режется по
        // внутренней границе — выглядит так, будто он скроллится внутри рамки.
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(if (compact) Spacing.md else Spacing.xl),
    ) {
        if (!uiState.loaded) {
            LoadingState("loading services…")
            return@Column
        }
        if (services.isEmpty()) {
            EmptyState("No service has reported any metrics yet")
            return@Column
        }

        val totalInstances = services.sumOf { it.instances }
        val onRange: (Range) -> Unit = { range -> onAction(OverviewUiAction.SelectRange(range)) }

        if (compact) {
            Text(
                "Overview",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            RangeSelector(uiState.range, onRange, Modifier.fillMaxWidth())
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(
                        "${services.size} " + plural(services.size, "SERVICE") +
                            " · $totalInstances " + plural(totalInstances, "INSTANCE"),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = MetrikMono,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Text(
                        "Overview",
                        style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                RangeSelector(uiState.range, onRange)
            }
        }

        if (firing.isNotEmpty()) {
            if (compact) {
                AlertsHeroCompact(firing, onOpenAlerts)
            } else {
                AlertsHero(firing)
            }
        }

        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                services.forEach { service ->
                    MobileServiceRow(service, firing, uiState.sparklines[service.id]) { onSelect(service) }
                }
            }
        } else {
            ServiceGrid(services, firing, uiState.sparklines, onSelect)
        }
    }
}

/**
 * Сетка карточек сервисов — обычный `Column` из `Row`, а не `LazyVerticalGrid`: экран целиком
 * теперь одна прокручиваемая область ([OverviewScreen]), а вложенный ленивый скролл внутри чужого
 * `verticalScroll` либо падает с «measured with an infinity maximum height», либо требует
 * фиксированной высоты, которую тут взять неоткуда. Сервисов на инсталляцию — единицы-десятки
 * (см. комментарий в [NavRail]), так что обычная раскладка ничего не стоит по производительности.
 */
@Composable
private fun ServiceGrid(
    services: List<ServiceSummary>,
    alerts: List<AlertView>,
    sparklines: Map<Long, List<ChartPoint>>,
    onSelect: (ServiceSummary) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        services.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                row.forEach { service ->
                    Box(Modifier.weight(1f)) {
                        ServiceGridCard(service, alerts, sparklines[service.id]) { onSelect(service) }
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun AlertsHero(firing: List<AlertView>) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp, bottomEnd = 32.dp, bottomStart = 40.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = Spacing.xxl - Spacing.xs, vertical = Spacing.xl + Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xxl),
    ) {
        Column(Modifier.widthIn(min = 200.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(
                    firing.size.toString(),
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Column {
                    Text(
                        plural(firing.size, "alert"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        plural(firing.size, "is", "are") + " firing",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            // Здесь стояло «гистерезис 5 окон, кулдаун 15 мин, всё уже ушло в Telegram» — три
            // утверждения, и все три неверны. Гистерезис задаётся правилом (`windows`) и виден на
            // экране порогов, а не фиксирован пятёркой; кулдаун — 30 минут; а доставка вообще не
            // факт: без настроенного бота сервер поднимает заглушку и не отправляет ничего.
            // Экран не знает, настроен ли Telegram, — узнать это можно только кнопкой «Send a test».
            // Поэтому теперь здесь правило, а не отчёт о доставке.
            Text(
                "A rule fires after several windows in a row and repeats no more often than the cooldown. " +
                    "Delivery to Telegram happens when a bot is configured.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            firing.forEach { alert -> FiringAlertRow(alert) }
        }
    }
}

/**
 * Компактный hero для мобильной раскладки (`docs/design/metrik-expressive.html`, «mobile: обзор»):
 * число+подпись в одной строке с кнопкой «Смотреть», список алертов ниже. В отличие от макета кнопка
 * не декоративная — переводит на экран «Алерты» ([onOpenAlerts]), эндпоинт для этого не нужен.
 */
@Composable
private fun AlertsHeroCompact(
    firing: List<AlertView>,
    onOpenAlerts: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomEnd = 28.dp, bottomStart = 36.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = Spacing.xl - Spacing.xs, vertical = Spacing.lg + Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text(
                firing.size.toString(),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                plural(firing.size, "alert") + "\nfiring",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Box(
                Modifier
                    .weight(1f, fill = false)
                    .height(34.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(MaterialTheme.colorScheme.onErrorContainer)
                    .clickable(onClick = onOpenAlerts)
                    .padding(horizontal = Spacing.lg),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "View",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.errorContainer,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            firing.forEach { alert -> FiringAlertRowCompact(alert) }
        }
    }
}

@Composable
private fun FiringAlertRowCompact(alert: AlertView) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.10f))
            .padding(horizontal = Spacing.md + Spacing.xs, vertical = Spacing.sm + Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm + Spacing.xs),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onErrorContainer))
        Text(
            alert.service,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = MetrikMono,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onErrorContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            alert.ruleId,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = MetrikMono,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun FiringAlertRow(alert: AlertView) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.10f))
            .padding(horizontal = Spacing.lg + Spacing.xs, vertical = Spacing.md + Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Box(
            Modifier
                .height(10.dp)
                .widthIn(min = 10.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onErrorContainer),
        )
        Text(
            alert.service,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = MetrikMono,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.widthIn(min = 160.dp),
        )
        Box(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.16f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(
                alert.ruleId,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = MetrikMono,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        Text(
            alert.detail ?: "",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ServiceGridCard(
    service: ServiceSummary,
    alerts: List<AlertView>,
    sparkline: List<ChartPoint>?,
    onClick: () -> Unit,
) {
    val firing = service.firingAlerts.isNotEmpty()
    val shape =
        if (firing) {
            RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomEnd = 28.dp, bottomStart = 40.dp)
        } else {
            RoundedCornerShape(28.dp)
        }
    val bg = if (firing) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface
    val fg = if (firing) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
    val dim = if (firing) MaterialTheme.colorScheme.error else MetrikExtra.dim
    val chipBg: Color
    val chipFg: Color
    val accent: Color
    when {
        firing -> {
            chipBg = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.16f)
            chipFg = MaterialTheme.colorScheme.onErrorContainer
            accent = MaterialTheme.colorScheme.onErrorContainer
        }

        service.clockSkew -> {
            chipBg = MaterialTheme.colorScheme.tertiaryContainer
            chipFg = MaterialTheme.colorScheme.onTertiaryContainer
            accent = MaterialTheme.colorScheme.tertiary
        }

        else -> {
            chipBg = MetrikExtra.healthyContainer
            chipFg = MetrikExtra.onHealthyContainer
            accent = MetrikExtra.healthy
        }
    }
    val stateLabel =
        when {
            firing -> "${service.firingAlerts.size} " + plural(service.firingAlerts.size, "alert")
            service.clockSkew -> "clock skew"
            service.lastSeenAt == null -> "silent"
            else -> "ok"
        }
    val note =
        when {
            firing -> {
                alerts
                    .filter { it.service == service.name && it.ruleId in service.firingAlerts }
                    .joinToString("; ") { it.detail ?: it.ruleId }
            }

            service.clockSkew -> {
                "the instance clock has drifted — some windows were dropped"
            }

            else -> {
                null
            }
        }
    val neverSeen = service.lastSeenAt == null
    val rpsLabel = if (neverSeen) "—" else format(service.requestsPerSecond)
    val errLabel = if (neverSeen) "—" else "${format(service.errorRate * 100)} %"
    val p95Label = if (neverSeen) "—" else "${format(service.p95Ms)} ms"

    Column(
        Modifier
            .heightIn(min = 214.dp)
            .clip(shape)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    service.name,
                    fontFamily = MetrikMono,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = fg,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Box(Modifier.clip(RoundedCornerShape(9.dp)).background(chipBg).padding(horizontal = 9.dp, vertical = 3.dp)) {
                        Text(stateLabel, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = chipFg)
                    }
                    Box(Modifier.clip(RoundedCornerShape(9.dp)).background(chipBg).padding(horizontal = 9.dp, vertical = 3.dp)) {
                        Text(
                            "${service.instances} inst.",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = MetrikMono,
                            color = chipFg,
                        )
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(rpsLabel, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = fg)
                Text("rps", style = MaterialTheme.typography.labelSmall, fontFamily = MetrikMono, color = dim)
            }
        }

        Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(16.dp))) {
            if (!sparkline.isNullOrEmpty() && sparkline.any { it.value != null }) {
                Sparkline(sparkline, accent, Modifier.fillMaxSize())
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement =
                Arrangement.spacedBy(Spacing.xl + Spacing.xs),
        ) {
            Column {
                Text(errLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = fg)
                Text("errors", style = MaterialTheme.typography.labelSmall, fontFamily = MetrikMono, color = dim)
            }
            Column {
                Text(p95Label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = fg)
                Text("p95 ≈ ±20 %", style = MaterialTheme.typography.labelSmall, fontFamily = MetrikMono, color = dim)
            }
            if (note != null) {
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (firing) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.tertiary,
                    textAlign = TextAlign.End,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Строка сервиса для мобильной раскладки («mobile: обзор» в макете) — имя+ошибки/p95 слева,
 * спарклайн, rps справа. Те же данные, что в [ServiceGridCard], просто в один ряд вместо карточки:
 * на 390dp ширины трёхколоночная сетка не помещается.
 */
@Composable
private fun MobileServiceRow(
    service: ServiceSummary,
    alerts: List<AlertView>,
    sparkline: List<ChartPoint>?,
    onClick: () -> Unit,
) {
    val firing = service.firingAlerts.isNotEmpty()
    val shape =
        if (firing) {
            RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 28.dp)
        } else {
            RoundedCornerShape(20.dp)
        }
    val bg = if (firing) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface
    val fg = if (firing) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
    val dim = if (firing) MaterialTheme.colorScheme.error else MetrikExtra.dim
    val accent =
        when {
            firing -> MaterialTheme.colorScheme.onErrorContainer
            service.clockSkew -> MaterialTheme.colorScheme.tertiary
            else -> MetrikExtra.healthy
        }
    val neverSeen = service.lastSeenAt == null
    val rpsLabel = if (neverSeen) "—" else format(service.requestsPerSecond)
    val errLabel = if (neverSeen) "—" else "${format(service.errorRate * 100)} %"
    val p95Label = if (neverSeen) "—" else "${format(service.p95Ms)} ms"

    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.lg + Spacing.xs, vertical = Spacing.md + Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md + Spacing.xs),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                service.name,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = MetrikMono,
                fontWeight = FontWeight.SemiBold,
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text("$errLabel err.", style = MaterialTheme.typography.labelSmall, fontFamily = MetrikMono, color = dim)
                Text("p95 $p95Label", style = MaterialTheme.typography.labelSmall, fontFamily = MetrikMono, color = dim)
            }
        }
        Box(Modifier.width(72.dp).height(32.dp)) {
            if (!sparkline.isNullOrEmpty() && sparkline.any { it.value != null }) {
                Sparkline(sparkline, accent, Modifier.fillMaxSize())
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(rpsLabel, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = fg)
            Text("rps", style = MaterialTheme.typography.labelSmall, fontFamily = MetrikMono, color = dim)
        }
    }
}
