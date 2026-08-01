package ru.workinprogress.metrik.wire

// Кодирование HTTP-статуса в ключ серии.
// Компромисс и его причина — docs/research/research-architecture.md §Р10:
// успешный трафик однороден и хранится классом, ошибочный — точным кодом, потому что
// 401 против 404 и 500 против 503 это разные инциденты.

/** Ответа не было: исключение до отправки, обрыв соединения. */
const val STATUS_NO_RESPONSE: Int = 0

/**
 * Кодирует HTTP-статус для поля `c`.
 *
 * `0` — ответа не было, `1…5` — класс статуса, `400…599` — точный код.
 */
fun encodeStatus(httpStatus: Int): Int =
    when {
        httpStatus < 100 -> STATUS_NO_RESPONSE
        httpStatus in 400..599 -> httpStatus
        httpStatus < 400 -> httpStatus / 100
        else -> 5
    }

/** Класс статуса (`1…5`) для закодированного значения — по нему считается error rate. */
fun statusClassOf(encoded: Int): Int = if (encoded >= 100) encoded / 100 else encoded

/** Считается ли ответ ошибкой сервера. Именно это правило смотрит алерт `error_rate`. */
fun isServerError(encoded: Int): Boolean = statusClassOf(encoded) == 5
