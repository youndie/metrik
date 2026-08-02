package ru.workinprogress.metrik.web.core.domain

/**
 * Опрос раз в 30 с: окно агрегации минутное, real-time тут ничего не добавит (docs/research §Р6).
 */
const val REFRESH_MS = 30_000L

/**
 * «Сейчас» в миллисекундах эпохи — зависимость, а не вызов часов из ViewModel: иначе экраны нечем
 * тестировать, а «обновлено N с назад» невозможно проверить, не подождав эти N секунд.
 */
fun interface TimeSource {
    fun nowMs(): Long
}
