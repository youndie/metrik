package ru.workinprogress.metrik.web

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.workinprogress.metrik.api.AlertRuleView
import ru.workinprogress.metrik.api.AlertView
import ru.workinprogress.metrik.api.ServiceSummary

/**
 * Алерты — единственный экран с элементом записи в макете («Отправить тестовое», «Заглушить»).
 * Ни то, ни другое не подключено: под них нет эндпоинтов в docs/api/endpoint-query.md (единственная
 * мутация в контракте — `PUT .../alerts` для порогов, и то админская). Кнопки нарисованы по
 * макету, но не имеют действия — см. итоговый отчёт.
 *
 * Весь экран — одна прокручиваемая область: «Горят сейчас», «История срабатываний» и «Пороги»
 * раньше скроллились независимо внутри своих карточек, теперь один `verticalScroll` на весь Column,
 * а карточки просто растут по контенту.
 *
 * На мобильной раскладке ([compact]) панели «Пороги» нет вовсе — в макете (`docs/design/
 * metrik-expressive.html`, «mobile: алерты») её тоже нет, редактирование порогов — desktop/admin
 * сценарий, а кнопка «Отправить тестовое» на мобильном не поместилась бы рядом с заголовком без
 * потери читаемости, поэтому там тоже её нет.
 */
@Composable
fun AlertsScreen(
    client: MetrikClient,
    services: List<ServiceSummary>,
    alerts: List<AlertView>,
    compact: Boolean = false,
    nowMs: () -> Long,
) {
    val firing = alerts.filter { it.state.equals("firing", ignoreCase = true) }
    val totalRules =
        alerts
            .map { it.ruleId }
            .distinct()
            .size
            .coerceAtLeast(firing.size)

    // Панель «Пороги» привязана к сервису — экран «Алерты» верхнеуровневый и ни к какому сервису
    // не привязан, поэтому берём тот, где сейчас что-то горит, а если не горит ничего — первый по
    // списку. Без этого панель было бы нечего показывать.
    val rulesService = services.firstOrNull { it.firingAlerts.isNotEmpty() } ?: services.firstOrNull()
    var rules by remember { mutableStateOf<List<AlertRuleView>?>(null) }
    var rulesDenied by remember { mutableStateOf(false) }

    LaunchedEffect(rulesService?.id, compact) {
        rules = null
        rulesDenied = false
        if (compact) return@LaunchedEffect
        val id = rulesService?.id ?: return@LaunchedEffect
        runCatching { client.adminAlertRules(id) }
            .onSuccess { rules = it }
            .onFailure { rulesDenied = true }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(if (compact) Spacing.md else Spacing.xl),
    ) {
        if (compact) {
            Text(
                "Алерты",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(
                        "TELEGRAM · КУЛДАУН 15 МИН",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Text(
                        "Алерты",
                        style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                // Декоративная кнопка — эндпоинта «отправить тестовое сообщение» в контракте нет.
                Box(
                    Modifier
                        .height(56.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = Spacing.xl + Spacing.xs),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Отправить тестовое",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }

        if (compact) {
            if (firing.isNotEmpty()) {
                FiringRulesCard(firing, totalRules, nowMs, compact = true)
            }
            HistoryCard(alerts, nowMs)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xl - Spacing.xs)) {
                Column(Modifier.weight(1.25f), verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                    if (firing.isNotEmpty()) {
                        FiringRulesCard(firing, totalRules, nowMs, compact = false)
                    }
                    HistoryCard(alerts, nowMs)
                }

                Column(Modifier.weight(1f)) {
                    ThresholdsCard(rulesService, rules, rulesDenied)
                }
            }
        }
    }
}

@Composable
private fun FiringRulesCard(
    firing: List<AlertView>,
    totalRules: Int,
    nowMs: () -> Long,
    compact: Boolean,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(
                RoundedCornerShape(
                    topStart = if (compact) 28.dp else 32.dp,
                    topEnd = if (compact) 28.dp else 32.dp,
                    bottomEnd = if (compact) 28.dp else 32.dp,
                    bottomStart = if (compact) 36.dp else 40.dp,
                ),
            ).background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = if (compact) Spacing.lg + Spacing.xs else Spacing.xl, vertical = Spacing.xl - Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        if (compact) {
            Text(
                "Горят сейчас · ${firing.size}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(
                    "Горят сейчас",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    "${firing.size} из $totalRules правил",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        firing.forEach { alert ->
            if (compact) FiringRuleRowCompact(alert, nowMs) else FiringRuleRow(alert, nowMs)
        }
    }
}

@Composable
private fun FiringRuleRow(
    alert: AlertView,
    nowMs: () -> Long,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.10f))
            .padding(horizontal = Spacing.lg + Spacing.xs, vertical = Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onErrorContainer))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                "${alert.service} · ${alert.ruleId}",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                alert.detail ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            relativeAgo(nowMs(), alert.since),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.error,
        )
        // «Заглушить» — тоже декоративная: mute-эндпоинта в контракте нет.
        Box(
            Modifier
                .height(36.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.onErrorContainer)
                .padding(horizontal = Spacing.lg),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Заглушить",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.errorContainer,
            )
        }
    }
}

/** Компактная строка горящего правила — «mobile: алерты»: детали в две строки, кнопки под ними. */
@Composable
private fun FiringRuleRowCompact(
    alert: AlertView,
    nowMs: () -> Long,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.10f))
            .padding(horizontal = Spacing.lg, vertical = Spacing.md + Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm + Spacing.xs)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onErrorContainer))
            Text(
                alert.service,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            Text(
                relativeAgo(nowMs(), alert.since),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            alert.detail ?: "",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            // Обе кнопки декоративные — см. заголовок файла: mute- и переходного эндпоинтов нет,
            // а «к сервису» в макете подразумевает переход, для которого этому экрану неоткуда
            // взять callback навигации по сервису (алерт хранит только имя, не id).
            Box(
                Modifier
                    .height(32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.onErrorContainer)
                    .padding(horizontal = Spacing.md + Spacing.xs),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Заглушить 1 ч",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.errorContainer,
                )
            }
            Box(
                Modifier
                    .height(32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .padding(horizontal = Spacing.md + Spacing.xs),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "К сервису",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun HistoryCard(
    alerts: List<AlertView>,
    nowMs: () -> Long,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(32.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(
            "История срабатываний",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (alerts.isEmpty()) {
            EmptyState("нет данных")
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val sorted = alerts.sortedByDescending { it.since }
                sorted.forEachIndexed { index, alert ->
                    HistoryRow(alert, nowMs(), historyRowShape(index, sorted.size))
                }
            }
        }
    }
}

private fun historyRowShape(
    index: Int,
    size: Int,
): Shape =
    when {
        size == 1 -> RoundedCornerShape(12.dp)
        index == 0 -> RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 8.dp, bottomEnd = 8.dp)
        index == size - 1 -> RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 20.dp, bottomEnd = 20.dp)
        else -> RoundedCornerShape(8.dp)
    }

@Composable
private fun HistoryRow(
    alert: AlertView,
    nowMs: Long,
    shape: Shape,
) {
    val active = alert.state.equals("firing", ignoreCase = true)
    val dotColor = if (active) MaterialTheme.colorScheme.error else MetrikExtra.neutralDot
    val stateBg = if (active) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val stateFg = if (active) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = Spacing.md + Spacing.xs, vertical = Spacing.sm + Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md + Spacing.xs),
    ) {
        Box(Modifier.size(8.dp).clip(if (active) CircleShape else RoundedCornerShape(2.dp)).background(dotColor))
        Text(
            "${alert.service} · ${alert.ruleId}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Box(Modifier.clip(RoundedCornerShape(8.dp)).background(stateBg).padding(horizontal = 9.dp, vertical = 2.dp)) {
            Text(
                alertStateLabel(alert.state),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = stateFg,
            )
        }
        Text(
            relativeAgo(nowMs, alert.since),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun ThresholdsCard(
    service: ServiceSummary?,
    rules: List<AlertRuleView>?,
    denied: Boolean,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(32.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                "Пороги" + (service?.let { " · ${it.name}" } ?: ""),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Пустое поле = значение инсталляции. Переопределение подсвечено.",
                style = MaterialTheme.typography.bodySmall,
                color = MetrikExtra.dim,
            )
        }

        when {
            service == null -> {
                EmptyState("нет сервисов")
            }

            denied -> {
                EmptyState("нет доступа к порогам — нужен admin-тир (см. docs/api/endpoint-query.md)")
            }

            rules == null -> {
                LoadingState("загружаем пороги…")
            }

            rules.isEmpty() -> {
                EmptyState("нет правил")
            }

            else -> {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    rules.forEach { rule -> ThresholdCard(rule) }
                }
            }
        }
    }
}

@Composable
private fun ThresholdCard(rule: AlertRuleView) {
    val overridden = !rule.inherited
    val bg = if (overridden) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    val fg =
        if (overridden) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else if (rule.enabled) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MetrikExtra.dim
        }
    val dim = if (overridden) MaterialTheme.colorScheme.primary else MetrikExtra.dim
    val fieldBg =
        if (overridden) {
            MaterialTheme.colorScheme.onPrimaryContainer.copy(
                alpha = 0.12f,
            )
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        }
    val trackColor = if (rule.enabled) MaterialTheme.colorScheme.primary else MetrikExtra.toggleTrackOff
    val knobColor = if (rule.enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.outline

    Column(
        Modifier
            .fillMaxWidth()
            .clip(
                RoundedCornerShape(24.dp),
            ).background(bg)
            .padding(horizontal = Spacing.lg + Spacing.xs, vertical = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    rule.ruleId,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = fg,
                )
                Text(
                    if (overridden) "переопределено для сервиса" else "значение по умолчанию",
                    style = MaterialTheme.typography.bodySmall,
                    color = dim,
                )
            }
            // Переключатель — «состояние правила включено», без интерактивности: `PUT` порогов
            // не входит в задание (единственная мутация в контракте — вне рамок этого экрана).
            Box(
                Modifier
                    .size(width = 52.dp, height = 32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(trackColor)
                    .padding(3.dp),
                contentAlignment = if (rule.enabled) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(knobColor))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            ThresholdField("ПОРОГ", formatThreshold(rule.threshold), fieldBg, fg, dim, Modifier.weight(1f))
            ThresholdField("ОКОН", rule.windows.toString(), fieldBg, fg, dim, Modifier.weight(1f))
            ThresholdField("MIN COUNT", rule.minCount.toString(), fieldBg, fg, dim, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ThresholdField(
    label: String,
    value: String,
    background: Color,
    foreground: Color,
    labelColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(background).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = labelColor)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color = foreground,
        )
    }
}

private fun formatThreshold(value: Double): String = format(value)
