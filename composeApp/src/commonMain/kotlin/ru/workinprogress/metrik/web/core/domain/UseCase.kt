package ru.workinprogress.metrik.web.core.domain

import kotlin.coroutines.cancellation.CancellationException

/** Одна операция домена. ViewModel зависит от юзкейсов, а не от репозиториев. */
interface UseCase<in P, out R> {
    suspend operator fun invoke(params: P): Result<R>
}

/**
 * `runCatching`, который **не** глотает `CancellationException`.
 *
 * Обычный `runCatching` в suspend-коде ломает отмену: упавший в `Result.failure` `CancellationException`
 * не долетает до корутины, и отменённая загрузка продолжает жить. В дашборде это особенно заметно —
 * загрузки перезапускаются на каждую смену диапазона.
 */
suspend inline fun <T> suspendRunCatching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (cause: Throwable) {
        Result.failure(cause)
    }

/** Юзкейсы без параметров. */
data object NoParams
