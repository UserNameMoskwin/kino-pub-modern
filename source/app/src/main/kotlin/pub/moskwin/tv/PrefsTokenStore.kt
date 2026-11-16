package pub.moskwin.tv

import android.content.Context
import pub.moskwin.core.TokenStore
import pub.moskwin.core.Tokens

/** Токены в приватном файле настроек приложения. */
class PrefsTokenStore(context: Context) : TokenStore {
    private val prefs = context.getSharedPreferences("tokens", Context.MODE_PRIVATE)

    @Synchronized
    override fun load(): Tokens? {
        val access = prefs.getString(ACCESS, null) ?: return null
        val refresh = prefs.getString(REFRESH, null) ?: return null
        return Tokens(access, refresh, prefs.getLong(EXPIRES, 0))
    }

    @Synchronized
    override fun save(tokens: Tokens?) {
        // commit, а не apply: refresh_token одноразовый, потерять его при падении нельзя.
        prefs.edit().apply {
            if (tokens == null) clear() else {
                putString(ACCESS, tokens.accessToken)
                putString(REFRESH, tokens.refreshToken)
                putLong(EXPIRES, tokens.expiresAt)
            }
        }.commit()
    }

    private companion object {
        const val ACCESS = "access"
        const val REFRESH = "refresh"
        const val EXPIRES = "expires_at"
    }
}
