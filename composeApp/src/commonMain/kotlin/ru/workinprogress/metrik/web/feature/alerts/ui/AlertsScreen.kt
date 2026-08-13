package ru.workinprogress.metrik.web.feature.alerts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.datetime.TimeZone
import org.koin.compose.viewmodel.koinViewModel
import ru.workinprogress.metrik.api.AlertRuleView
import ru.workinprogress.metrik.api.AlertView
import ru.workinprogress.metrik.api.ServiceSummary
import ru.workinprogress.metrik.web.ui.EmptyState
import ru.workinprogress.metrik.web.ui.LoadingState
import ru.workinprogress.metrik.web.ui.MetrikExtra
import ru.workinprogress.metrik.web.ui.MetrikMono
import ru.workinprogress.metrik.web.ui.Spacing
import ru.workinprogress.metrik.web.ui.absoluteAgo
import ru.workinprogress.metrik.web.ui.alertStateLabel
import ru.workinprogress.metrik.web.ui.format
import ru.workinprogress.metrik.web.ui.relativeAgo

/**
 * Экран «Алерты»: горящие сейчас, история переходов и пороги правил (см. [AlertsViewModel]).
 *
 * Мобильная раскладка показывает только «Горят сейчас» и историю: панель порогов — это форма на
 * три поля в ряд, на 390dp её честнее не показывать вовсе, чем показывать нажимаемой наполовину.
 * Заглушение там фиксированное («Заглушить 1 ч» буквально из макета), без выбора срока.
 */
@Composable
fun AlertsScreen(
    viewModel: AlertsViewModel = koinViewModel(),
    compact: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var lastError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is AlertsUiEvent.ShowError -> lastError = event.message
            }
        }
    }

    AlertsContent(
        uiState = uiState,
        onAction = viewModel::onAction,
        lastError = lastError,
        compact = compact,
        contentPadding = contentPadding,
    )
}

/** Стейтлес — всё через [uiState]/[onAction], про ViewModel ничего не знает. */
@Composable
fun AlertsContent(
    uiState: AlertsUiState,
    onAction: (AlertsUiAction) -> Unit,
    lastError: String? = null,
    compact: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val zone = remember { TimeZone.currentSystemDefault() }
    val nowMs = uiState.nowMs
    val firing = uiState.firing
    val onMute: (AlertView, Long) -> Unit = { alert, minutes -> onAction(AlertsUiAction.MuteAlert(alert, minutes)) }
    val onUnmute: (AlertView) -> Unit = { alert -> onAction(AlertsUiAction.UnmuteAlert(alert)) }

    Column(
        // Паддинг применяется ПОСЛЕ verticalScroll и потому едет вместе с контентом. Если
        // повесить его снаружи (на контейнер шелла), вьюпорт сужается, и контент режется по
        // внутренней границе — выглядит так, будто он скроллится внутри рамки.
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(if (compact) Spacing.md else Spacing.xl),
    ) {
        if (compact) {
            Text(
                "Alerts",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(
                        // Кулдаун — 30 минут (`AlertWorker.cooldownMs`), в подписи стояло 15.
                        // Цифра в интерфейсе, разошедшаяся с кодом, врёт ровно так же, как
                        // неверная метрика: её читают и на неё полагаются.
                        "TELEGRAM · 30 MIN COOLDOWN",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = MetrikMono,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Text(
                        "Alerts",
                        style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                TestAlertButton(uiState.testState) { onAction(AlertsUiAction.SendTest) }
            }
        }

        if (compact) {
            if (firing.isNotEmpty()) {
                FiringRulesCard(firing, uiState.totalRules, nowMs, zone, compact = true, onMute = onMute, onUnmute = onUnmute)
            }
            HistoryCard(uiState.history, uiState.historyError, nowMs, zone)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xl - Spacing.xs)) {
                Column(Modifier.weight(1.25f), verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                    if (firing.isNotEmpty()) {
                        FiringRulesCard(firing, uiState.totalRules, nowMs, zone, compact = false, onMute = onMute, onUnmute = onUnmute)
                    }
                    HistoryCard(uiState.history, uiState.historyError, nowMs, zone)
                }

                Column(Modifier.weight(1f)) {
                    ThresholdsCard(uiState, onAction, nowMs, zone)
                }
            }
        }

        // Отказ мутации виден и тогда, когда карточка правила уже уехала из вида.
        if (lastError != null) {
            Text(
                "last error: $lastError",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Сколько времени предлагаем на заглушение (десктоп) — по макету дефолт «1 ч». */
private val MuteDurations = listOf(15L to "15m", 60L to "1h", 240L to "4h", 24 * 60L to "24h")

/**
 * Кнопка/статус тестового уведомления (M-81) — единственный способ узнать, что Telegram настроен,
 * не дожидаясь настоящей аварии. `delivered == false` показываем как есть, не как «отправлено»:
 * это единственный сигнал, что нотификатор молчит.
 */
@Composable
private fun TestAlertButton(
    state: TestAlertState,
    onSend: () -> Unit,
) {
    // Кнопка намеренно тихая: обвод вместо заливки, обычный вес, компактная высота.
    // Это служебная проверка настройки, которую нажимают раз в жизни, — заливка primary
    // делала её самым громким элементом экрана, где главное всё-таки горящие алерты.
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Box(
            Modifier
                .height(36.dp)
                .clip(RoundedCornerShape(18.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
                .clickable(enabled = state != TestAlertState.Sending, onClick = onSend)
                .padding(horizontal = Spacing.lg),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (state == TestAlertState.Sending) "Sending…" else "Send a test",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (val s = state) {
            is TestAlertState.Done -> {
                Text(
                    // Честно: false — это не «попробуем позже», а «Telegram не настроен».
                    if (s.delivered) "delivered" else "not delivered — Telegram is not configured",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (s.delivered) MetrikExtra.healthy else MaterialTheme.colorScheme.error,
                )
            }

            is TestAlertState.Failed -> {
                Text("error: ${s.message}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            }

            else -> {}
        }
    }
}

/**
 * Заглушить/снять — общий контрол для «Горят сейчас» и панели «Пороги» (M-81). Заглушённое правило
 * показывается честно: строка остаётся горящей/видимой, рядом — до какого момента молчит доставка.
 */
@Composable
private fun MuteControl(
    mutedUntil: Long?,
    nowMs: Long,
    zone: TimeZone,
    onMute: (minutes: Long) -> Unit,
    onUnmute: () -> Unit,
    background: Color = MaterialTheme.colorScheme.errorContainer,
    foreground: Color = MaterialTheme.colorScheme.onErrorContainer,
) {
    val muted = mutedUntil != null && mutedUntil > nowMs
    var pickerOpen by remember { mutableStateOf(false) }

    when {
        muted -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    "silent until " + absoluteAgo(nowMs, mutedUntil, zone, labelToday = true),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = MetrikMono,
                    color = foreground,
                )
                Box(
                    Modifier
                        .height(28.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(background)
                        .clickable(onClick = onUnmute)
                        .padding(horizontal = Spacing.md),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Unmute", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = foreground)
                }
            }
        }

        pickerOpen -> {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                MuteDurations.forEach { (minutes, label) ->
                    Box(
                        Modifier
                            .height(28.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(background)
                            .clickable {
                                pickerOpen = false
                                onMute(minutes)
                            }.padding(horizontal = Spacing.sm + Spacing.xs),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = foreground)
                    }
                }
            }
        }

        else -> {
            Box(
                Modifier
                    .height(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(background)
                    .clickable { pickerOpen = true }
                    .padding(horizontal = Spacing.lg),
                contentAlignment = Alignment.Center,
            ) {
                Text("Mute", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = foreground)
            }
        }
    }
}

@Composable
private fun FiringRulesCard(
    firing: List<AlertView>,
    totalRules: Int,
    nowMs: Long,
    zone: TimeZone,
    compact: Boolean,
    onMute: (AlertView, Long) -> Unit,
    onUnmute: (AlertView) -> Unit,
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
                "Firing now · ${firing.size}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(
                    "Firing now",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    "${firing.size} of $totalRules rules",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = MetrikMono,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        firing.forEach { alert ->
            if (compact) {
                FiringRuleRowCompact(alert, nowMs, zone, onMute = { onMute(alert, 60L) }, onUnmute = { onUnmute(alert) })
            } else {
                FiringRuleRow(alert, nowMs, zone, onMute = { minutes -> onMute(alert, minutes) }, onUnmute = { onUnmute(alert) })
            }
        }
    }
}

@Composable
private fun FiringRuleRow(
    alert: AlertView,
    nowMs: Long,
    zone: TimeZone,
    onMute: (minutes: Long) -> Unit,
    onUnmute: () -> Unit,
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
                fontFamily = MetrikMono,
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
            // Длительность текущего срабатывания — относительное время здесь уместнее абсолютного
            // (см. `Formatting.kt`, `relativeAgo`): это «горит уже N», а не запись в истории.
            relativeAgo(nowMs, alert.since),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = MetrikMono,
            color = MaterialTheme.colorScheme.error,
        )
        MuteControl(
            mutedUntil = alert.mutedUntil,
            nowMs = nowMs,
            zone = zone,
            onMute = onMute,
            onUnmute = onUnmute,
            background = MaterialTheme.colorScheme.onErrorContainer,
            foreground = MaterialTheme.colorScheme.errorContainer,
        )
    }
}

/** Компактная строка горящего правила — «mobile: алерты»: детали в две строки, кнопки под ними. */
@Composable
private fun FiringRuleRowCompact(
    alert: AlertView,
    nowMs: Long,
    zone: TimeZone,
    onMute: () -> Unit,
    onUnmute: () -> Unit,
) {
    val mutedUntil = alert.mutedUntil
    val muted = mutedUntil != null && mutedUntil > nowMs

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
                fontFamily = MetrikMono,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            Text(
                relativeAgo(nowMs, alert.since),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = MetrikMono,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            alert.detail ?: "",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        if (muted) {
            Text(
                "silent until " + absoluteAgo(nowMs, mutedUntil, zone, labelToday = true),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = MetrikMono,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            // «Заглушить 1 ч» — буквально из мобильного кадра макета: фиксированный час, без выбора
            // срока (тот есть только на десктопе, см. [MuteControl]).
            Box(
                Modifier
                    .height(32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.onErrorContainer)
                    .clickable(onClick = if (muted) onUnmute else onMute)
                    .padding(horizontal = Spacing.md + Spacing.xs),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (muted) "Unmute" else "Mute for 1h",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.errorContainer,
                )
            }
            // «К сервису» остаётся декоративной: у этого экрана нет колбэка навигации по сервису
            // (в App.kt переход к сервису живёт в MobileShell/DesktopShell, а не здесь).
            Box(
                Modifier
                    .height(32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .padding(horizontal = Spacing.md + Spacing.xs),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Open service",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

/**
 * История срабатываний — `GET /api/alerts/history` (см. `docs/api/endpoint-query.md`): переходы
 * правил FIRING↔OK с отметкой времени и деталью, не текущее состояние.
 *
 * [history] `null` — ответа ещё не было; пустой список — правила действительно ни разу не
 * переключались. Различать обязательно: иначе «ещё не загрузили» выглядело бы как «ничего не
 * происходило». Ошибка при уже полученных данных не стирает их, а подписывается отдельной строкой —
 * показанные записи настоящие, просто могли устареть.
 */
@Composable
private fun HistoryCard(
    history: List<AlertView>?,
    error: String?,
    nowMs: Long,
    zone: TimeZone,
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
            "History",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (error != null && history != null) {
            Text(
                "the data may be stale: $error",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        when {
            history == null && error != null -> {
                EmptyState("could not load the history: $error")
            }

            history == null -> {
                LoadingState("loading history…")
            }

            history.isEmpty() -> {
                EmptyState("no data")
            }

            else -> {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val sorted = history.sortedByDescending { it.since }
                    sorted.forEachIndexed { index, alert ->
                        HistoryRow(alert, nowMs, zone, historyRowShape(index, sorted.size))
                    }
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
    zone: TimeZone,
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
            fontFamily = MetrikMono,
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
            // Абсолютное время (M-83): история вперемешку со старыми записями, «N назад» тут менее
            // честно, чем конкретный момент — `labelToday` подписывает и сегодняшние записи «сегодня»,
            // чтобы не путать с текущими (см. `Formatting.kt`, `absoluteAgo`).
            absoluteAgo(nowMs, alert.since, zone, labelToday = true),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = MetrikMono,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun ThresholdsCard(
    uiState: AlertsUiState,
    onAction: (AlertsUiAction) -> Unit,
    nowMs: Long,
    zone: TimeZone,
) {
    val service = uiState.rulesService
    val rules = uiState.rules
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
                "Thresholds" + (service?.let { " · ${it.name}" } ?: ""),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Changes are saved with the button. Overrides are highlighted.",
                style = MaterialTheme.typography.bodySmall,
                color = MetrikExtra.dim,
            )
        }

        when {
            service == null -> {
                EmptyState("no services")
            }

            uiState.rulesDenied -> {
                EmptyState("no access to thresholds — the admin tier is required (see docs/api/endpoint-query.md)")
            }

            rules == null -> {
                LoadingState("loading thresholds…")
            }

            rules.isEmpty() -> {
                EmptyState("no rules")
            }

            else -> {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    rules.forEach { rule ->
                        ThresholdCard(
                            rule = rule,
                            error = uiState.ruleErrors[rule.ruleId],
                            saving = rule.ruleId in uiState.savingRuleIds,
                            nowMs = nowMs,
                            zone = zone,
                            onAction = onAction,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Порог одного правила — теперь форма (M-82): порог/окна/min count редактируются, «Сохранить»
 * появляется только когда что-то реально изменилось, отказ сервера показывается текстом, а не
 * молчанием. Заглушение — тем же [MuteControl], что и в «Горят сейчас».
 */
@Composable
private fun ThresholdCard(
    rule: AlertRuleView,
    error: String?,
    saving: Boolean,
    nowMs: Long,
    zone: TimeZone,
    onAction: (AlertsUiAction) -> Unit,
) {
    // Значения полей до сабмита — чисто презентационное состояние: доменного смысла у недописанного
    // числа нет, в UiState ему делать нечего.
    var thresholdText by remember(rule.ruleId, rule.threshold) { mutableStateOf(formatThreshold(rule.threshold)) }
    var windowsText by remember(rule.ruleId, rule.windows) { mutableStateOf(rule.windows.toString()) }
    var minCountText by remember(rule.ruleId, rule.minCount) { mutableStateOf(rule.minCount.toString()) }
    var enabled by remember(rule.ruleId, rule.enabled) { mutableStateOf(rule.enabled) }
    var validationError by remember(rule.ruleId) { mutableStateOf<String?>(null) }

    val dirty =
        thresholdText != formatThreshold(rule.threshold) ||
            windowsText != rule.windows.toString() ||
            minCountText != rule.minCount.toString() ||
            enabled != rule.enabled

    fun save() {
        val thresholdValue = thresholdText.replace(',', '.').toDoubleOrNull()
        val windowsValue = windowsText.toIntOrNull()
        val minCountValue = minCountText.toIntOrNull()
        validationError =
            when {
                thresholdValue == null || thresholdValue < 0 -> "threshold must be a non-negative number"
                minCountValue == null || minCountValue < 0 -> "min count must be a non-negative integer"
                windowsValue == null || windowsValue < 1 -> "windows must be at least 1"
                else -> null
            }
        if (validationError != null || thresholdValue == null || windowsValue == null || minCountValue == null) return

        onAction(
            AlertsUiAction.SaveRule(
                rule.copy(threshold = thresholdValue, minCount = minCountValue, windows = windowsValue, enabled = enabled),
            ),
        )
    }

    val shownError = validationError ?: error

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
            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        }
    val trackColor = if (enabled) MaterialTheme.colorScheme.primary else MetrikExtra.toggleTrackOff
    val knobColor = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.outline

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(bg)
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
                    fontFamily = MetrikMono,
                    fontWeight = FontWeight.SemiBold,
                    color = fg,
                )
                Text(
                    if (overridden) "overridden for this service" else "installation default",
                    style = MaterialTheme.typography.bodySmall,
                    color = dim,
                )
            }
            Box(
                Modifier
                    .size(width = 52.dp, height = 32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(trackColor)
                    .clickable { enabled = !enabled }
                    .padding(3.dp),
                contentAlignment = if (enabled) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(knobColor))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            ThresholdInputField("THRESHOLD", thresholdText, { thresholdText = it }, fieldBg, fg, dim, Modifier.weight(1f))
            ThresholdInputField("WINDOWS", windowsText, { windowsText = it }, fieldBg, fg, dim, Modifier.weight(1f), integer = true)
            ThresholdInputField("MIN COUNT", minCountText, { minCountText = it }, fieldBg, fg, dim, Modifier.weight(1f), integer = true)
        }

        if (shownError != null) {
            Text(shownError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            MuteControl(
                mutedUntil = rule.mutedUntil,
                nowMs = nowMs,
                zone = zone,
                onMute = { minutes -> onAction(AlertsUiAction.MuteRule(rule, minutes)) },
                onUnmute = { onAction(AlertsUiAction.UnmuteRule(rule)) },
            )
            if (dirty) {
                Box(
                    Modifier
                        .height(32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(enabled = !saving) { save() }
                        .padding(horizontal = Spacing.lg),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (saving) "Saving…" else "Save",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ThresholdInputField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    background: Color,
    foreground: Color,
    labelColor: Color,
    modifier: Modifier = Modifier,
    integer: Boolean = false,
) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(background).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, fontFamily = MetrikMono, color = labelColor)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle =
                MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = MetrikMono,
                    fontWeight = FontWeight.SemiBold,
                    color = foreground,
                ),
            keyboardOptions = KeyboardOptions(keyboardType = if (integer) KeyboardType.Number else KeyboardType.Decimal),
            cursorBrush = SolidColor(foreground),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun formatThreshold(value: Double): String = format(value)
