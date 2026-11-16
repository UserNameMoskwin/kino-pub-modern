package pub.moskwin.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DeviceCode(
    /** Код для опроса сервера. Пользователю не показывается. */
    val code: String,
    /** Код, который пользователь вводит на сайте. */
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri") val verificationUri: String,
    @SerialName("expires_in") val expiresIn: Long = 300,
    val interval: Long = 5,
)

@Serializable
internal data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long = 3600,
)

/** Вход по коду на экране и обновление токена. */
class Auth internal constructor(
    private val transport: Transport,
    private val credentials: ClientCredentials,
    private val store: TokenStore,
    private val now: () -> Long,
) {
    private val refreshLock = Mutex()

    val isLoggedIn: Boolean get() = store.load() != null

    suspend fun requestDeviceCode(): DeviceCode {
        val response = transport.send("oauth2/device", form = clientForm("device_code"))
        if (!response.ok) throw response.toException()
        return LenientJson.decodeFromString(DeviceCode.serializer(), response.body)
    }

    /**
     * Один опрос. Возвращает false, пока пользователь не ввёл код,
     * true — когда вход выполнен и токены сохранены.
     */
    suspend fun pollOnce(deviceCode: DeviceCode): Boolean {
        val response = transport.send(
            "oauth2/device",
            form = clientForm("device_token") + ("code" to deviceCode.code),
        )
        if (response.ok) {
            saveTokens(response)
            return true
        }
        val error = response.toException()
        if (error.error == "authorization_pending") return false
        throw error
    }

    /** Опрашивает сервер до входа. Возвращает false, если код истёк. */
    suspend fun awaitLogin(deviceCode: DeviceCode): Boolean {
        val deadline = now() + deviceCode.expiresIn
        val intervalMs = deviceCode.interval.coerceAtLeast(1) * 1000
        while (now() < deadline) {
            delay(intervalMs)
            if (pollOnce(deviceCode)) return true
        }
        return false
    }

    /** Действующий access_token. Заранее обновляет его, если до конца срока меньше минуты. */
    suspend fun accessToken(): String {
        val tokens = store.load() ?: throw LoginRequiredException()
        if (tokens.expiresAt - now() > REFRESH_MARGIN_SEC) return tokens.accessToken
        return refresh(staleAccessToken = tokens.accessToken)
    }

    /**
     * Обновляет токены. Если другой запрос уже успел обновить их
     * (в хранилище не [staleAccessToken]), повторного обновления не делает:
     * refresh_token одноразовый.
     */
    suspend fun refresh(staleAccessToken: String): String = refreshLock.withLock {
        val tokens = store.load() ?: throw LoginRequiredException()
        if (tokens.accessToken != staleAccessToken) return tokens.accessToken
        val response = transport.send(
            "oauth2/token",
            form = clientForm("refresh_token") + ("refresh_token" to tokens.refreshToken),
            // Повтор на другом адресе после потери ответа сжёг бы refresh_token.
            failover = false,
        )
        if (response.ok) return saveTokens(response).accessToken
        if (response.status in 400..499 && response.status != 429) {
            store.save(null)
            throw LoginRequiredException("сессия истекла: ${response.toException().message}")
        }
        throw response.toException()
    }

    fun logoutLocally() = store.save(null)

    private fun saveTokens(response: RawResponse): Tokens {
        val parsed = LenientJson.decodeFromString(TokenResponse.serializer(), response.body)
        return Tokens(parsed.accessToken, parsed.refreshToken, now() + parsed.expiresIn).also(store::save)
    }

    private fun clientForm(grantType: String): Map<String, String?> = mapOf(
        "grant_type" to grantType,
        "client_id" to credentials.clientId,
        "client_secret" to credentials.clientSecret,
    )

    private companion object {
        const val REFRESH_MARGIN_SEC = 60
    }
}
