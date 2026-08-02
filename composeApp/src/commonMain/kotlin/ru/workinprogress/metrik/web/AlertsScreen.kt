package ru.workinprogress.metrik.web

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import ru.workinprogress.metrik.api.AlertRuleView
import ru.workinprogress.metrik.api.AlertView
import ru.workinprogress.metrik.api.ServiceSummary

/**
 * Алерты. Три мутации из макета подключены к серверу, который их теперь умеет (M-81, M-82):
 * «Отправить тестовое» ([TestAlertButton]), «Заглушить»/«Снять» ([MuteControl]) на каждое горящее
 * правило и в панели «Пороги», и сама панель «Пороги» — редактируемая форма, а не витрина.
 *
 * Весь экран — одна прокручиваемая область: «Горят сейчас», «История срабатываний» и «Пороги»
 * раньше скроллились независимо внутри своих карточек, теперь один `verticalScroll` на весь Column,
 * а карточки просто растут по контенту.
 *
 * На мобильной раскладке ([compact]) панели «Пороги» нет вовсе — в макете (`docs/design/
 * metrik-expressive.html`, «mobile: алерты») её тоже нет, редактирование порогов — desktop/admin
 * сценарий. Кнопка «Отправить тестовое» там же отсутствует по той же причине, что и раньше: не
 * помещается рядом с заголовком без потери читаемости. Заглушение на мобильном — фиксированный час
 * («Заглушить 1 ч» буквально из макета), без выбора срока, который есть на десктопе.
 *
 * Проп [alerts] — активные алерты из общего опроса `App.kt` (`GET /api/alerts`, на сервере это
 * `WHERE state = 'FIRING'`), им кормится только «Горят сейчас». Историю экран запрашивает сам, см.
 * [HistoryCard].
 */
@Composable
fun AlertsScreen(
    client: MetrikClient,
    services: List<ServiceSummary>,
    alerts: List<AlertView>,
    compact: Boolean = false,
    nowMs: () -> Long,
) {
    val scope = rememberCoroutineScope()
    val zone = remember { TimeZone.currentSystemDefault() }

    // AlertView хранит только имя сервиса, не id (см. `ru.workinprogress.metrik.api.AlertView`) —
    // резолвим через список сервисов, который экрану и так передан.
    val serviceIdByName = remember(services) { services.associate { it.name to it.id } }

    val firing = alerts.filter { it.state.equals("firing", ignoreCase = true) }
    val totalRules =
        alerts
            .map { it.ruleId }
            .distinct()
            .size
            .coerceAtLeast(firing.size)

    // История срабатываний — свой эндпоинт и свой опрос. Общий `alerts` сверху отдаёт только
    // горящие сейчас правила, поэтому «историей» из него получалась бы копия «Горят сейчас», в
    // которой не может появиться ни одна погасшая запись. `/api/alerts/history` — это записанные
    // переходы (FIRING/OK, последние 100, см. `AlertWorker.history()`), то есть то, что карточка и
    // обещает. Период тот же, что у общего опроса: история меняется не чаще.
    var history by remember { mutableStateOf<List<AlertView>?>(null) }
    var historyError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            runCatching { client.alertHistory() }
                .onSuccess {
                    history = it
                    historyError = null
                }.onFailure { cause -> historyError = cause.message ?: "не удалось получить историю" }
            delay(REFRESH_MS)
        }
    }

    // Панель «Пороги» привязана к сервису — экран «Алерты» верхнеуровневый и ни к какому сервису
    // не привязан, поэтому берём тот, где сейчас что-то горит, а если не горит ничего — первый по
    // списку. Без этого панель было бы нечего показывать.
    val rulesService = services.firstOrNull { it.firingAlerts.isNotEmpty() } ?: services.firstOrNull()
    var rules by remember { mutableStateOf<List<AlertRuleView>?>(null) }
    var rulesDenied by remember { mutableStateOf(false) }

    suspend fun reloadRules(id: Long) {
        runCatching { client.adminAlertRules(id) }
            .onSuccess {
                rules = it
                rulesDenied = false
            }.onFailure { rulesDenied = true }
    }

    LaunchedEffect(rulesService?.id, compact) {
        rules = null
        rulesDenied = false
        if (compact) return@LaunchedEffect
        val id = rulesService?.id ?: return@LaunchedEffect
        reloadRules(id)
    }

    // Заглушение из «Горят сейчас» бьёт по своему сервису напрямую; если это тот же сервис, что
    // сейчас открыт в панели «Пороги», подтягиваем её тоже — иначе панель показывала бы устаревший
    // mutedUntil до следующего LaunchedEffect. Сам список `alerts` — проп сверху (опрос раз в 30 с,
    // см. `App.kt`), его мгновенно не обновить без переписывания опроса на уровне приложения; в
    // худшем случае горящая строка покажет старое состояние заглушения до ближайшего тика опроса —
    // это тот же компромисс, на котором и так стоит весь дашборд (real-time здесь не нужен).
    fun muteAlert(
        alert: AlertView,
        minutes: Long,
    ) {
        val id = serviceIdByName[alert.service] ?: return
        scope.launch {
            runCatching { client.muteAlertRule(id, alert.ruleId, minutes) }
            if (id == rulesService?.id) reloadRules(id)
        }
    }

    fun unmuteAlert(alert: AlertView) {
        val id = serviceIdByName[alert.service] ?: return
        scope.launch {
            runCatching { client.unmuteAlertRule(id, alert.ruleId) }
            if (id == rulesService?.id) reloadRules(id)
        }
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
                        fontFamily = MetrikMono,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Text(
                        "Алерты",
                        style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                TestAlertButton(client)
            }
        }

        if (compact) {
            if (firing.isNotEmpty()) {
                FiringRulesCard(firing, totalRules, nowMs, zone, compact = true, onMute = ::muteAlert, onUnmute = ::unmuteAlert)
            }
            HistoryCard(history, historyError, nowMs, zone)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xl - Spacing.xs)) {
                Column(Modifier.weight(1.25f), verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                    if (firing.isNotEmpty()) {
                        FiringRulesCard(firing, totalRules, nowMs, zone, compact = false, onMute = ::muteAlert, onUnmute = ::unmuteAlert)
                    }
                    HistoryCard(history, historyError, nowMs, zone)
                }

                Column(Modifier.weight(1f)) {
                    ThresholdsCard(client, rulesService, rules, rulesDenied, nowMs, zone) { updated -> rules = updated }
                }
            }
        }
    }
}

/** Сколько времени предлагаем на заглушение (десктоп) — по макету дефолт «1 ч». */
private val MuteDurations = listOf(15L to "15 мин", 60L to "1 ч", 240L to "4 ч", 24 * 60L to "24 ч")

/**
 * Кнопка/статус тестового уведомления (M-81) — единственный способ узнать, что Telegram настроен,
 * не дожидаясь настоящей аварии. `delivered == false` показываем как есть, не как «отправлено»:
 * это единственный сигнал, что нотификатор молчит.
 */
@Composable
private fun TestAlertButton(client: MetrikClient) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<TestAlertState>(TestAlertState.Idle) }

    // Кнопка намеренно тихая: обвод вместо заливки, обычный вес, компактная высота.
    // Это служебная проверка настройки, которую нажимают раз в жизни, — заливка primary
    // делала её самым громким элементом экрана, где главное всё-таки горящие алерты.
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Box(
            Modifier
                .height(36.dp)
                .clip(RoundedCornerShape(18.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
                .clickable(enabled = state != TestAlertState.Sending) {
                    state = TestAlertState.Sending
                    scope.launch {
                        state =
                            runCatching { client.sendTestAlert() }
                                .fold(
                                    onSuccess = { delivered -> TestAlertState.Done(delivered) },
                                    onFailure = { e -> TestAlertState.Failed(e.message ?: "нет связи с сервером") },
                                )
                    }
                }.padding(horizontal = Spacing.lg),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (state == TestAlertState.Sending) "Отправляем…" else "Отправить тестовое",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (val s = state) {
            is TestAlertState.Done -> {
                Text(
                    // Честно: false — это не «попробуем позже», а «Telegram не настроен».
                    if (s.delivered) "доставлено" else "не доставлено — Telegram не настроен",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (s.delivered) MetrikExtra.healthy else MaterialTheme.colorScheme.error,
                )
            }

            is TestAlertState.Failed -> {
                Text("ошибка: ${s.message}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            }

            else -> {}
        }
    }
}

private sealed interface TestAlertState {
    data object Idle : TestAlertState

    data object Sending : TestAlertState

    data class Done(
        val delivered: Boolean,
    ) : TestAlertState

    data class Failed(
        val message: String,
    ) : TestAlertState
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
                    "молчит до " + absoluteAgo(nowMs, mutedUntil, zone, labelToday = true),
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
                    Text("Снять", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = foreground)
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
                Text("Заглушить", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = foreground)
            }
        }
    }
}

@Composable
private fun FiringRulesCard(
    firing: List<AlertView>,
    totalRules: Int,
    nowMs: () -> Long,
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
    nowMs: () -> Long,
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
            relativeAgo(nowMs(), alert.since),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = MetrikMono,
            color = MaterialTheme.colorScheme.error,
        )
        MuteControl(
            mutedUntil = alert.mutedUntil,
            nowMs = nowMs(),
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
    nowMs: () -> Long,
    zone: TimeZone,
    onMute: () -> Unit,
    onUnmute: () -> Unit,
) {
    val mutedUntil = alert.mutedUntil
    val muted = mutedUntil != null && mutedUntil > nowMs()

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
                relativeAgo(nowMs(), alert.since),
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
                "молчит до " + absoluteAgo(nowMs(), mutedUntil, zone, labelToday = true),
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
                    if (muted) "Снять" else "Заглушить 1 ч",
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
                    "К сервису",
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
    nowMs: () -> Long,
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
            "История срабатываний",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (error != null && history != null) {
            Text(
                "данные могли устареть: $error",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        when {
            history == null && error != null -> {
                EmptyState("не удалось загрузить историю: $error")
            }

            history == null -> {
                LoadingState("загружаем историю…")
            }

            history.isEmpty() -> {
                EmptyState("нет данных")
            }

            else -> {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val sorted = history.sortedByDescending { it.since }
                    sorted.forEachIndexed { index, alert ->
                        HistoryRow(alert, nowMs(), zone, historyRowShape(index, sorted.size))
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
    client: MetrikClient,
    service: ServiceSummary?,
    rules: List<AlertRuleView>?,
    denied: Boolean,
    nowMs: () -> Long,
    zone: TimeZone,
    onRulesChanged: (List<AlertRuleView>) -> Unit,
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
                "Изменения сохраняются по кнопке. Переопределение подсвечено.",
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
                    rules.forEach { rule ->
                        ThresholdCard(client, service.id, rule, nowMs, zone, onRulesChanged)
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
    client: MetrikClient,
    serviceId: Long,
    rule: AlertRuleView,
    nowMs: () -> Long,
    zone: TimeZone,
    onRulesChanged: (List<AlertRuleView>) -> Unit,
) {
    val scope = rememberCoroutineScope()

    var thresholdText by remember(rule.ruleId, rule.threshold) { mutableStateOf(formatThreshold(rule.threshold)) }
    var windowsText by remember(rule.ruleId, rule.windows) { mutableStateOf(rule.windows.toString()) }
    var minCountText by remember(rule.ruleId, rule.minCount) { mutableStateOf(rule.minCount.toString()) }
    var enabled by remember(rule.ruleId, rule.enabled) { mutableStateOf(rule.enabled) }
    var error by remember(rule.ruleId) { mutableStateOf<String?>(null) }
    var saving by remember(rule.ruleId) { mutableStateOf(false) }

    val dirty =
        thresholdText != formatThreshold(rule.threshold) ||
            windowsText != rule.windows.toString() ||
            minCountText != rule.minCount.toString() ||
            enabled != rule.enabled

    fun save() {
        val thresholdValue = thresholdText.replace(',', '.').toDoubleOrNull()
        val windowsValue = windowsText.toIntOrNull()
        val minCountValue = minCountText.toIntOrNull()
        error =
            when {
                thresholdValue == null || thresholdValue < 0 -> "порог должен быть неотрицательным числом"
                minCountValue == null || minCountValue < 0 -> "min count должен быть неотрицательным целым"
                windowsValue == null || windowsValue < 1 -> "окон должно быть не меньше 1"
                else -> null
            }
        if (error != null || thresholdValue == null || windowsValue == null || minCountValue == null) return

        saving = true
        scope.launch {
            runCatching {
                client.updateAlertRule(
                    serviceId,
                    rule.copy(threshold = thresholdValue, minCount = minCountValue, windows = windowsValue, enabled = enabled),
                )
            }.onSuccess { updated ->
                onRulesChanged(updated)
                error = null
            }.onFailure { e ->
                error = "не удалось сохранить: ${e.message ?: "ошибка сервера"}"
            }
            saving = false
        }
    }

    fun mute(minutes: Long) {
        scope.launch {
            runCatching { client.muteAlertRule(serviceId, rule.ruleId, minutes) }
                .onSuccess(onRulesChanged)
                .onFailure { error = "не удалось заглушить: ${it.message ?: "ошибка сервера"}" }
        }
    }

    fun unmute() {
        scope.launch {
            runCatching { client.unmuteAlertRule(serviceId, rule.ruleId) }
                .onSuccess(onRulesChanged)
                .onFailure { error = "не удалось снять заглушение: ${it.message ?: "ошибка сервера"}" }
        }
    }

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
                    if (overridden) "переопределено для сервиса" else "значение по умолчанию",
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
            ThresholdInputField("ПОРОГ", thresholdText, { thresholdText = it }, fieldBg, fg, dim, Modifier.weight(1f))
            ThresholdInputField("ОКОН", windowsText, { windowsText = it }, fieldBg, fg, dim, Modifier.weight(1f), integer = true)
            ThresholdInputField("MIN COUNT", minCountText, { minCountText = it }, fieldBg, fg, dim, Modifier.weight(1f), integer = true)
        }

        if (error != null) {
            Text(error.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            MuteControl(rule.mutedUntil, nowMs(), zone, onMute = ::mute, onUnmute = ::unmute)
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
                        if (saving) "Сохраняем…" else "Сохранить",
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
