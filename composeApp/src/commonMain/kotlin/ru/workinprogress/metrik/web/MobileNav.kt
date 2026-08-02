package ru.workinprogress.metrik.web

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.workinprogress.metrik.api.ServiceSummary

/** Раздел, на который сейчас указывает нижняя навигация мобильной раскладки. */
enum class MobileTab { OVERVIEW, ALERTS, SERVICES }

/**
 * Нижняя навигация — мобильный аналог [NavRail] (`docs/design/metrik-expressive.html`, кадры
 * «mobile: ···»): три вкладки, «Алерты» с тем же счётчиком горящих правил, что и в рельсе. Счётчик
 * показан только когда вкладка не выбрана — как в макете: активная вкладка и так подсвечена цветом
 * тревоги, дублировать число поверх было бы избыточно.
 */
@Composable
fun BottomNavBar(
    selected: MobileTab,
    firingAlertCount: Int,
    onSelect: (MobileTab) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(68.dp)
            .clip(RoundedCornerShape(34.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BottomNavItem(
            label = "Обзор",
            dotShape = RoundedCornerShape(3.dp),
            active = selected == MobileTab.OVERVIEW,
            activeBg = MaterialTheme.colorScheme.primaryContainer,
            activeFg = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.weight(1f),
            onClick = { onSelect(MobileTab.OVERVIEW) },
        )
        BottomNavItem(
            label = "Алерты",
            dotShape = CircleShape,
            active = selected == MobileTab.ALERTS,
            activeBg = MaterialTheme.colorScheme.errorContainer,
            activeFg = MaterialTheme.colorScheme.onErrorContainer,
            badgeCount = firingAlertCount.takeIf { it > 0 && selected != MobileTab.ALERTS },
            modifier = Modifier.weight(1f),
            onClick = { onSelect(MobileTab.ALERTS) },
        )
        BottomNavItem(
            label = "Сервисы",
            dotShape = RoundedCornerShape(3.dp),
            active = selected == MobileTab.SERVICES,
            activeBg = MaterialTheme.colorScheme.primaryContainer,
            activeFg = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.weight(1f),
            onClick = { onSelect(MobileTab.SERVICES) },
        )
    }
}

@Composable
private fun BottomNavItem(
    label: String,
    dotShape: Shape,
    active: Boolean,
    activeBg: Color,
    activeFg: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badgeCount: Int? = null,
) {
    Box(modifier.fillMaxHeight()) {
        Column(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(if (active) activeBg else Color.Transparent)
                .clickable(onClick = onClick),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(12.dp).clip(dotShape).background(if (active) activeFg else MaterialTheme.colorScheme.onSurfaceVariant))
            Spacer(Modifier.height(3.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                color = if (active) activeFg else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (badgeCount != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 18.dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    badgeCount.toString(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

/**
 * Список сервисов на весь экран — мобильная замена списку сервисов в рельсе (там его в узком окне
 * показать негде), третья вкладка нижней навигации. В отличие от карточек «Обзора» здесь нет
 * спарклайнов и диапазона: это просто быстрый переход к сервису, тот же набор данных, что в
 * [NavRail] (имя, точка состояния, rps за «живой» период).
 */
@Composable
fun MobileServicesListScreen(
    services: List<ServiceSummary>,
    selectedServiceId: Long?,
    onSelect: (ServiceSummary) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(
            "${services.size} " + pluralRu(services.size, "СЕРВИС", "СЕРВИСА", "СЕРВИСОВ"),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.outline,
        )
        Text(
            "Сервисы",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (services.isEmpty()) {
            EmptyState("Пока ни один сервис не прислал метрик")
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                services.forEach { service ->
                    MobileServiceListRow(service, selected = service.id == selectedServiceId) { onSelect(service) }
                }
            }
        }
    }
}

@Composable
private fun MobileServiceListRow(
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
    val rpsLabel = if (service.lastSeenAt == null) "—" else format(service.requestsPerSecond)

    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(dotColor))
        Text(
            service.name,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            rpsLabel,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        )
    }
}
