package ru.workinprogress.metrik.web

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Относительное «N назад» вместо часов настенных часов.
 *
 * В commonMain (jvm + wasmJs) нет библиотеки дат — добавлять её ради форматирования запрещено
 * условиями задачи. Вывести из epoch-миллисекунд человеческое «12:41» означало бы либо тащить
 * java.time (недоступен на wasmJs), либо считать по UTC и выдавать это за локальное время — а это
 * уже нечестно перед пользователем в другом часовом поясе. Относительное «N мин назад» этой
 * проблемы не имеет и для мониторинга ничем не хуже.
 */
fun relativeAgo(
    nowMs: Long,
    atMs: Long,
): String {
    val diffSeconds = ((nowMs - atMs).coerceAtLeast(0)) / 1000
    return when {
        diffSeconds < 60 -> "$diffSeconds с"
        diffSeconds < 3600 -> "${diffSeconds / 60} мин"
        diffSeconds < 86_400 -> "${diffSeconds / 3600} ч"
        else -> "${diffSeconds / 86_400} дн"
    }
}

/**
 * Русское число + слово в нужной форме («1 алерт», «2 алерта», «5 алертов»).
 * Стандартное mod10/mod100 правило: тексты в дашборде не должны звучать как машинный перевод.
 */
fun pluralRu(
    n: Int,
    one: String,
    few: String,
    many: String,
): String {
    val mod10 = n % 10
    val mod100 = n % 100
    return when {
        mod10 == 1 && mod100 != 11 -> one
        mod10 in 2..4 && mod100 !in 12..14 -> few
        else -> many
    }
}

/** Русская подпись состояния правила алертинга — с запасным вариантом для неизвестных состояний. */
fun alertStateLabel(state: String): String =
    when (state.lowercase()) {
        "firing", "active" -> "горит"
        "resolved", "ok" -> "погас"
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
