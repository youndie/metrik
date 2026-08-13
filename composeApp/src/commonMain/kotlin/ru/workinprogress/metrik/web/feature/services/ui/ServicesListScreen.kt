package ru.workinprogress.metrik.web.feature.services.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.workinprogress.metrik.api.ServiceSummary
import ru.workinprogress.metrik.web.ui.EmptyState
import ru.workinprogress.metrik.web.ui.MetrikExtra
import ru.workinprogress.metrik.web.ui.MetrikMono
import ru.workinprogress.metrik.web.ui.Spacing
import ru.workinprogress.metrik.web.ui.format
import ru.workinprogress.metrik.web.ui.plural

/**
 * Список сервисов на весь экран — мобильная замена списку сервисов в рельсе (там его в узком окне
 * показать негде), третья вкладка нижней навигации. В отличие от карточек «Обзора» здесь нет
 * спарклайнов и диапазона: это просто быстрый переход к сервису, тот же набор данных, что в
 * рельсе (имя, точка состояния, rps за «живой» период).
 */
@Composable
fun ServicesListScreen(
    services: List<ServiceSummary>,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    selectedServiceId: Long? = null,
    onSelect: (ServiceSummary) -> Unit,
) {
    Column(
        // Паддинг применяется ПОСЛЕ verticalScroll и потому едет вместе с контентом. Если
        // повесить его снаружи (на контейнер шелла), вьюпорт сужается, и контент режется по
        // внутренней границе — выглядит так, будто он скроллится внутри рамки.
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(
            "${services.size} " + plural(services.size, "SERVICE"),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = MetrikMono,
            color = MaterialTheme.colorScheme.outline,
        )
        Text(
            "Services",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (services.isEmpty()) {
            EmptyState("No service has reported any metrics yet")
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
            fontFamily = MetrikMono,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            rpsLabel,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = MetrikMono,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        )
    }
}
