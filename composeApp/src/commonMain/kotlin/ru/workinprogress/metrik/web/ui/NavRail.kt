package ru.workinprogress.metrik.web.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.workinprogress.metrik.api.ServiceSummary

/** Ширина рельса — как в макете: 268px превращены в dp напрямую (плотность макета взята за 1×). */
private val RAIL_WIDTH = 268.dp

/** Раздел, на который сейчас указывает рельс — не привязан к конкретному сервису. */
enum class TopRoute { OVERVIEW, ALERTS }

/**
 * Навигационный рельс — заменяет прежнюю навигацию «назад + вкладки» на десктопе (см.
 * `docs/design/metrik-expressive.html`, блок `rail`).
 *
 * Логотип-плашка, строка «обновлено N с назад», пункты «Обзор»/«Алерты» со счётчиком горящих
 * алертов, список сервисов с точкой состояния и rps, и внизу — карточка-подпись про опрос раз
 * в 30 секунд (то же окно агрегации, что и раньше — real-time тут ничего не добавит).
 */
@Composable
fun NavRail(
    services: List<ServiceSummary>,
    firingAlertCount: Int,
    updatedAgoLabel: String,
    topRoute: TopRoute?,
    selectedServiceId: Long?,
    onOverview: () -> Unit,
    onAlerts: () -> Unit,
    onSelectService: (ServiceSummary) -> Unit,
) {
    Column(
        Modifier
            .width(RAIL_WIDTH)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = Spacing.lg, vertical = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.xl + Spacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 22.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "m",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.ExtraBold,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Column {
                Text(
                    "metrik",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    updatedAgoLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = MetrikMono,
                    color = MetrikExtra.healthy,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            NavRailItem(
                label = "Обзор",
                dotShape = RoundedCornerShape(3.dp),
                dotColor =
                    if (topRoute == TopRoute.OVERVIEW) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                active = topRoute == TopRoute.OVERVIEW,
                onClick = onOverview,
            )
            NavRailItem(
                label = "Алерты",
                dotShape = CircleShape,
                dotColor = MaterialTheme.colorScheme.error,
                active = topRoute == TopRoute.ALERTS,
                onClick = onAlerts,
                trailing = {
                    if (firingAlertCount > 0) {
                        AlertCountBadge(firingAlertCount)
                    }
                },
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Text(
                "СЕРВИСЫ · ${services.size}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = MetrikMono,
                letterSpacing = 1.5.sp,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = Spacing.lg, bottom = Spacing.xs),
            )
            // Обычный Column, не LazyColumn: список сервисов на инсталляцию небольшой (единицы —
            // десятки), а LazyColumn всегда растягивается на весь доступный максимум высоты даже
            // с `fill = false` — тогда карточка-подпись внизу рельса отрывалась бы от списка.
            // `weight(fill = false)` здесь просто ограничивает список сверху высотой рельса и
            // включает скролл, если сервисов вдруг станет много.
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
            ) {
                services.forEach { service ->
                    ServiceRailRow(service, selected = service.id == selectedServiceId) { onSelectService(service) }
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Spacing.xl))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(horizontal = Spacing.lg, vertical = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(
                "Опрос раз в 30 с",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Text(
                "Окно агрегации — минута, чаще спрашивать нечего.",
                style = MaterialTheme.typography.bodySmall,
                color = MetrikExtra.dim,
            )
        }
    }
}

@Composable
private fun NavRailItem(
    label: String,
    dotShape: Shape,
    dotColor: Color,
    active: Boolean,
    onClick: () -> Unit,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.xl - Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Box(Modifier.size(10.dp).clip(dotShape).background(dotColor))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke(this)
    }
}

@Composable
private fun AlertCountBadge(count: Int) {
    Box(
        Modifier
            .height(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun ServiceRailRow(
    service: ServiceSummary,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val dotColor =
        when {
            service.firingAlerts.isNotEmpty() -> MaterialTheme.colorScheme.error
            service.clockSkew -> MaterialTheme.colorScheme.tertiary
            else -> MetrikExtra.healthy
        }
    // lastSeenAt == null — сервис ни разу не прислал пакет: rps не «0», а неизвестен.
    val rpsLabel = if (service.lastSeenAt == null) "—" else format(service.requestsPerSecond)

    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.lg + Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
        Text(
            service.name,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = MetrikMono,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            rpsLabel,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = MetrikMono,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        )
    }
}
