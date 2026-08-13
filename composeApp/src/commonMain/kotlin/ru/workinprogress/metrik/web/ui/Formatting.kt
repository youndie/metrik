package ru.workinprogress.metrik.web.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Относительное «N назад» — там, где это уместнее абсолютного (M-83): «обновлено N с назад» в
 * рельсе и «горит N мин» в активных алертах — длительность текущего состояния, а не запись в
 * истории, секунды/минуты точнее передают «прямо сейчас», чем часы настенных часов.
 */
fun relativeAgo(
    nowMs: Long,
    atMs: Long,
): String {
    val diffSeconds = ((nowMs - atMs).coerceAtLeast(0)) / 1000
    return when {
        diffSeconds < 60 -> "${diffSeconds}s"
        diffSeconds < 3600 -> "${diffSeconds / 60}m"
        diffSeconds < 86_400 -> "${diffSeconds / 3600}h"
        else -> "${diffSeconds / 86_400}d"
    }
}

/**
 * Абсолютное время в локальной зоне пользователя (M-83) — «12:41» сегодня, «вчера 09:14», «N дней
 * назад» для совсем старых записей; [labelToday] включает префикс «сегодня» и там, где день не
 * очевиден из контекста (история срабатываний вперемешку с записями за прошлые дни).
 *
 * `kotlinx-datetime`, а не `java.time`: `java.time` недоступен на wasmJs. Считать по UTC и выдавать
 * это за локальное время было бы ровно тем сортом вранья, который продукт запрещает себе в цифрах —
 * поэтому зона обязательный параметр, а не тихий дефолт на UTC.
 */
@OptIn(ExperimentalTime::class)
fun absoluteAgo(
    nowMs: Long,
    atMs: Long,
    zone: TimeZone,
    labelToday: Boolean = false,
): String {
    val at = Instant.fromEpochMilliseconds(atMs).toLocalDateTime(zone)
    val now = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone)
    val time = "${at.hour.pad2()}:${at.minute.pad2()}"
    val daysBetween = (now.date.toEpochDays() - at.date.toEpochDays()).toInt().coerceAtLeast(0)
    return when {
        daysBetween == 0 -> if (labelToday) "today $time" else time
        daysBetween == 1 -> "yesterday $time"
        else -> "$daysBetween " + plural(daysBetween, "day") + " ago"
    }
}

private fun Int.pad2(): String = if (this < 10) "0$this" else toString()

/**
 * Число и слово в нужной форме: «1 alert», «2 alerts».
 *
 * Раньше здесь жило русское правило mod10/mod100 с тремя формами. Английскому хватает двух, и
 * `-s` покрывает всё, что показывает дашборд (alert, service, instance, deploy, gap, window, day).
 * Слово с нестандартным множественным придётся передать вторым аргументом.
 */
fun plural(
    n: Int,
    one: String,
    many: String = one + "s",
): String = if (n == 1) one else many

/** Подпись состояния правила алертинга — с запасным вариантом для неизвестных состояний. */
fun alertStateLabel(state: String): String =
    when (state.lowercase()) {
        "firing", "active" -> "firing"
        "resolved", "ok" -> "resolved"
        else -> state
    }

/**
 * Цвет статус-кода: 5xx — ошибка сервера, 4xx — ошибка клиента, остальное — нейтрально.
 * Граница взята из самого кода ответа, а не из подобранного на глаз порога.
 */
@Composable
fun statusColor(status: Int): Color =
    when {
        status >= 500 -> MaterialTheme.colorScheme.error
        status >= 400 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
