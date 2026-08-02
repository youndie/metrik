package ru.workinprogress.metrik.web

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.workinprogress.metrik.api.AlertView
import ru.workinprogress.metrik.api.ServiceSummary

/**
 * Обзор: hero горящих алертов (если есть) и сетка карточек сервисов.
 *
 * Спарклайны карточек приходят отдельным поштучным опросом `timeseries` по каждому сервису — см.
 * [App] — поэтому это карта id → точки, а не часть [ServiceSummary].
 */
@Composable
fun OverviewScreen(
    services: List<ServiceSummary>,
    alerts: List<AlertView>,
    sparklines: Map<Long, List<ChartPoint>>,
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

    val firing = alerts.filter { it.state.equals("firing", ignoreCase = true) }
    val totalInstances = services.sumOf { it.instances }
    // Селектор диапазона визуальный: /api/services всегда отдаёт последние 5 минут и период не
    // принимает (docs/api/endpoint-query.md) — переключение здесь ничего не запросит заново.
    var cosmeticRange by remember { mutableStateOf(Range.HOUR) }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.xl)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    "${services.size} " + pluralRu(services.size, "СЕРВИС", "СЕРВИСА", "СЕРВИСОВ") +
                        " · $totalInstances " + pluralRu(totalInstances, "ИНСТАНС", "ИНСТАНСА", "ИНСТАНСОВ"),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.outline,
                )
                Text(
                    "Обзор",
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            RangeSelector(cosmeticRange, { cosmeticRange = it })
        }

        if (firing.isNotEmpty()) {
            AlertsHero(firing)
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            items(services, key = { it.id }) { service ->
                ServiceGridCard(service, alerts, sparklines[service.id]) { onSelect(service) }
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
                        pluralRu(firing.size, "алерт", "алерта", "алертов"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        "горят",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            Text(
                "Гистерезис 5 окон, кулдаун 15 мин. Всё уже ушло в Telegram.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            firing.forEach { alert -> FiringAlertRow(alert) }
        }
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
            fontFamily = FontFamily.Monospace,
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
                fontFamily = FontFamily.Monospace,
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
            firing -> "${service.firingAlerts.size} " + pluralRu(service.firingAlerts.size, "алерт", "алерта", "алертов")
            service.clockSkew -> "часы"
            service.lastSeenAt == null -> "молчит"
            else -> "ок"
        }
    val note =
        when {
            firing -> {
                alerts
                    .filter { it.service == service.name && it.ruleId in service.firingAlerts }
                    .joinToString("; ") { it.detail ?: it.ruleId }
            }

            service.clockSkew -> {
                "часы инстанса разъехались — часть окон отброшена"
            }

            else -> {
                null
            }
        }
    val neverSeen = service.lastSeenAt == null
    val rpsLabel = if (neverSeen) "—" else format(service.requestsPerSecond)
    val errLabel = if (neverSeen) "—" else "${format(service.errorRate * 100)} %"
    val p95Label = if (neverSeen) "—" else "${format(service.p95Ms)} мс"

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
                    fontFamily = FontFamily.Monospace,
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
                            "${service.instances} инст.",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = chipFg,
                        )
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(rpsLabel, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = fg)
                Text("rps", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = dim)
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
                Text("ошибки", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = dim)
            }
            Column {
                Text(p95Label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = fg)
                Text("p95 ≈ ±20 %", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = dim)
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
