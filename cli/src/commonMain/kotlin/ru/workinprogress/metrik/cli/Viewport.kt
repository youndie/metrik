package ru.workinprogress.metrik.cli

// Подгонка кадра под размер терминала.
//
// Mosaic перерисовывает кадр на месте: поднимает курсор на столько строк, сколько нарисовал, и
// пишет заново. Если кадр выше экрана, терминал прокручивается, курсор поднимается уже не туда, и
// куски прошлого кадра остаются на экране — ровно так на снимке 70×28 подсказка по клавишам
// оказалась напечатанной трижды.
//
// Отсюда правило: кадр не может быть выше и шире терминала. Обрезаем здесь, а не надеемся, что
// содержимое случайно поместится.

/** Обрезает строку по ширине, оставляя многоточие вместо хвоста. */
fun List<Cell>.clampWidth(width: Int): List<Cell> =
    when {
        width <= 0 -> emptyList()

        size <= width -> this

        width == 1 -> listOf(Cell('…', last().severity))

        // Многоточие, а не жёсткий обрыв: молча укороченное имя маршрута читается как другой
        // маршрут, а маршруты — это то, что человек пойдёт грепать в коде.
        else -> take(width - 1) + Cell('…', this[width - 1].severity)
    }

/**
 * Оставляет столько строк, сколько влезает, показывая окно вокруг [keepVisible].
 *
 * Просто взять первые N строк недостаточно: если выбранный сервис ниже границы экрана, человек
 * двигает курсор и не видит, куда он уехал.
 */
fun List<List<Cell>>.clampHeight(
    height: Int,
    keepVisible: Int = 0,
): List<List<Cell>> {
    if (height <= 0) return emptyList()
    if (size <= height) return this

    // Окно сдвигается ровно настолько, чтобы нужная строка оказалась внутри, и не дальше конца.
    val start = (keepVisible - height + 1).coerceIn(0, size - height)

    return subList(start, start + height)
}

/** Кадр целиком: обрезка по обеим осям одним вызовом, чтобы её нельзя было забыть наполовину. */
fun List<List<Cell>>.clamp(
    width: Int,
    height: Int,
    keepVisible: Int = 0,
): List<List<Cell>> = clampHeight(height, keepVisible).map { row -> row.clampWidth(width) }
