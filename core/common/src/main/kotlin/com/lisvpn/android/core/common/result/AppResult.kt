package com.lisvpn.android.core.common.result

/**
 * Domain-friendly Result type. Distinct from [kotlin.Result] so that:
 *  - we control supported error categories ([AppError]) and avoid leaking arbitrary [Throwable]s
 *    into the UI layer;
 *  - error mapping is centralised and testable.
 */
sealed interface AppResult<out T> {
    data class Success<T>(val value: T) : AppResult<T>
    data class Failure(val error: AppError, val cause: Throwable? = null) : AppResult<Nothing>
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(value))
    is AppResult.Failure -> this
}

inline fun <T> AppResult<T>.onSuccess(action: (T) -> Unit): AppResult<T> = also {
    if (it is AppResult.Success) action(it.value)
}

inline fun <T> AppResult<T>.onFailure(action: (AppError, Throwable?) -> Unit): AppResult<T> = also {
    if (it is AppResult.Failure) action(it.error, it.cause)
}

fun <T> AppResult<T>.getOrNull(): T? = (this as? AppResult.Success)?.value

inline fun <T> appResult(block: () -> T): AppResult<T> =
    try {
        AppResult.Success(block())
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        AppResult.Failure(AppError.from(e), e)
    }

sealed interface AppError {
    data object Network : AppError
    data object Timeout : AppError
    data class Server(val statusCode: Int) : AppError
    data object Unauthorized : AppError
    data class Parse(val reason: String) : AppError
    data class Vpn(val reason: String) : AppError
    data object NotFound : AppError
    data class Unknown(val reason: String?) : AppError

    companion object {
        fun from(t: Throwable): AppError = when (t) {
            is java.net.UnknownHostException, is java.net.ConnectException -> Network
            is java.net.SocketTimeoutException -> Timeout
            else -> Unknown(t.message)
        }
    }
}
