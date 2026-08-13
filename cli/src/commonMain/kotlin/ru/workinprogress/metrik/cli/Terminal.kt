package ru.workinprogress.metrik.cli

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.staticCFunction
import platform.posix.SIGINT
import platform.posix.SIGTERM
import platform.posix.exit
import platform.posix.fflush
import platform.posix.signal
import platform.posix.stdout

/**
 * Полноэкранный режим.
 *
 * Mosaic рисует инлайново: кадр перерисовывается на месте курсорными последовательностями, и все
 * кадры остаются в истории прокрутки. Альтернативного экрана у него нет — ни в API, ни в коде,
 * — поэтому переключаем буфер сами.
 *
 * `1049` — альтернативный экранный буфер, тот же, которым пользуются `top`, `htop` и `less`:
 * приложение занимает терминал целиком, а на выходе содержимое экрана возвращается нетронутым.
 *
 * **Главное здесь — не «войти», а гарантированно выйти.** Терминал, оставшийся в альтернативном
 * буфере, для человека выглядит как сломанная сессия, и чинится только `reset`. Поэтому выход
 * прикрыт с трёх сторон: обычный `q`, исключение и сигнал.
 *
 * Сырой байт escape в исходнике невидим и теряется при редактировании — только через `\u001b`.
 */
private const val ESC = "\u001b"

/** Войти в альтернативный буфер и спрятать курсор. */
private const val ENTER = "$ESC[?1049h$ESC[?25l"

/** Вернуть курсор и основной буфер — строго в обратном порядке. */
private const val LEAVE = "$ESC[?25h$ESC[?1049l"

@OptIn(ExperimentalForeignApi::class)
private fun emit(sequence: String) {
    print(sequence)
    fflush(stdout)
}

/**
 * Выполняет [block] в альтернативном буфере.
 *
 * `finally` закрывает нормальный выход и исключение. Сигналы через него не проходят вовсе —
 * для них ниже отдельные обработчики.
 */
@OptIn(ExperimentalForeignApi::class)
inline fun <T> fullScreen(block: () -> T): T {
    enterFullScreen()
    try {
        return block()
    } finally {
        leaveFullScreen()
    }
}

fun enterFullScreen() {
    emit(ENTER)
    installSignalHandlers()
}

fun leaveFullScreen() = emit(LEAVE)

/**
 * Выход из приложения.
 *
 * Mosaic не даёт способа завершить композицию — в его же примерах выходят `exitProcess`, минуя
 * любой `finally`. Значит буфер надо вернуть **до** выхода, и это единственная дверь наружу.
 */
fun quit(code: Int = 0): Nothing {
    leaveFullScreen()
    exit(code)
    error("unreachable")
}

/**
 * Ctrl+C и `kill` тоже обязаны вернуть экран.
 *
 * Обработчик — `staticCFunction`, он не может ничего захватывать, поэтому зовёт функцию верхнего
 * уровня. `print` из обработчика сигнала формально не async-signal-safe; здесь это допустимо:
 * альтернатива — оставить человека в сломанном терминале, а происходит это ровно один раз, прямо
 * перед выходом процесса.
 */
@OptIn(ExperimentalForeignApi::class)
private fun installSignalHandlers() {
    val handler =
        staticCFunction<Int, Unit> { code ->
            leaveFullScreen()
            exit(128 + code)
        }

    signal(SIGINT, handler)
    signal(SIGTERM, handler)
}
