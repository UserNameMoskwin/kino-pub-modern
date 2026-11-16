package pub.moskwin.core

import java.io.IOException

/** Сервер ответил ошибкой. */
class ApiException(val status: Int, val error: String?, message: String?) :
    IOException("HTTP $status${error?.let { " $it" } ?: ""}${message?.let { ": $it" } ?: ""}")

/** Ни один адрес из списка не ответил. */
class NoHostAvailableException(cause: Throwable?) :
    IOException("ни один адрес API не отвечает", cause)

/** Сессия недействительна: нужен повторный вход по коду. */
class LoginRequiredException(message: String = "нужен вход") : IOException(message)
