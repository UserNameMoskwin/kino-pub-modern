package pub.moskwin.core

import kotlinx.serialization.Serializable

@Serializable
data class Tokens(
    val accessToken: String,
    val refreshToken: String,
    /** Unix-время в секундах, когда access_token перестанет действовать. */
    val expiresAt: Long,
)

/** Хранилище токенов. Реализация в приложении пишет на диск. */
interface TokenStore {
    fun load(): Tokens?
    fun save(tokens: Tokens?)
}

class InMemoryTokenStore(private var tokens: Tokens? = null) : TokenStore {
    @Synchronized override fun load(): Tokens? = tokens
    @Synchronized override fun save(tokens: Tokens?) { this.tokens = tokens }
}

data class ClientCredentials(val clientId: String, val clientSecret: String)
