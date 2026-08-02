package ru.workinprogress.metrik.api

import kotlinx.serialization.Serializable

// Контракт между metrik-server и дашбордом. Живёт в :shared, потому что обе стороны — Kotlin,
// и расходиться описаниям незачем.

/** Шаг ряда. Сервер сам выбирает источник данных под шаг, клиент об этом не знает. */
@Serializable
enum class Step {
    MINUTE,
    HOUR,
    DAY,
}

/** Карточка сервиса в списке. */
@Serializable
data class ServiceSummary(
    val id: Long,
    val name: String,
    val requestsPerSecond: Double,
    val errorRate: Double,
    val p95Ms: Double,
    val lastSeenAt: Long?,
    val instances: Int,
    val clockSkew: Boolean,
    val firingAlerts: List<String> = emptyList(),
)

/**
 * Точка ряда.
 *
 * [partial] — окно, от которого пришли не все пакеты. UI обязан рисовать такую точку разрывом,
 * а не значением: «данных меньше» и «нагрузки меньше» это разные вещи.
 */
@Serializable
data class TimePoint(
    val at: Long,
    val requestsPerSecond: Double,
    val errorRate: Double,
    val p50Ms: Double,
    val p95Ms: Double,
    val maxMs: Long,
    val partial: Boolean = false,
)

/** Отметка деплоя на графике: «здесь выкатили 1.4.212». */
@Serializable
data class DeployMarker(
    val release: String,
    val at: Long,
)

@Serializable
data class TimeSeries(
    val step: Step,
    val points: List<TimePoint>,
    val deploys: List<DeployMarker> = emptyList(),
)

@Serializable
data class Overview(
    val service: String,
    val requests: Long,
    val errors: Long,
    val errorRate: Double,
    val p50Ms: Double,
    val p95Ms: Double,
    val maxMs: Long,
)

/** Строка таблицы маршрутов. Статус — точный код для 4xx/5xx, класс для остальных. */
@Serializable
data class RouteRow(
    val method: String,
    val route: String,
    val status: Int,
    val count: Long,
    val p50Ms: Double,
    val p95Ms: Double,
    val maxMs: Long,
)

@Serializable
data class SlowRow(
    val method: String,
    val route: String,
    val status: Int,
    val durationMs: Int,
    val at: Long,
)

/**
 * Точка системного ряда — единственное место, где разрез по инстансам сохранён.
 *
 * `heapMaxBytes` может отсутствовать: у нативного процесса нет максимума heap в смысле JVM.
 */
@Serializable
data class SystemPoint(
    val instance: String,
    val at: Long,
    val heapUsedBytes: Long,
    val heapMaxBytes: Long?,
    val cpuPermille: Int,
    val threads: Int,
    val gcCollections: Int?,
    val gcMs: Long?,
)

/** Состояние правила алертинга. */
@Serializable
data class AlertView(
    val service: String,
    val ruleId: String,
    val state: String,
    val since: Long,
    val detail: String? = null,
    /**
     * До какого момента правило заглушено.
     *
     * Заглушение молчит только в уведомлениях: правило продолжает считаться и гореть в UI.
     * Иначе «заглушил» превратилось бы в «сделал вид, что проблемы нет».
     */
    val mutedUntil: Long? = null,
)

/**
 * Горит ли правило прямо сейчас.
 *
 * `state` — строка протокола (`FIRING`/`OK`), и сравнение с ней расползалось по клиенту копиями
 * `state.equals("firing", ignoreCase = true)`. Смысл строки принадлежит контракту, а не экрану.
 */
val AlertView.isFiring: Boolean
    get() = state.equals("firing", ignoreCase = true)

/**
 * Ответ на `POST /api/admin/alerts/test`.
 *
 * Живёт здесь, а не копией в клиенте: копия контракта не только протухает, но и ломается —
 * Ktor подбирает сериализатор по типу, и для приватного класса на wasm этот поиск падает.
 */
@Serializable
data class TestNotificationResult(
    val delivered: Boolean,
)

/** Порог правила: либо дефолт инсталляции, либо переопределение сервиса. */
@Serializable
data class AlertRuleView(
    val ruleId: String,
    val threshold: Double,
    val minCount: Int,
    val windows: Int,
    val enabled: Boolean,
    val telegramChatId: String? = null,
    val inherited: Boolean = true,
    val mutedUntil: Long? = null,
)
