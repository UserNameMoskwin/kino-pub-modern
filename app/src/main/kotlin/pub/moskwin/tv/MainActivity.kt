package pub.moskwin.tv

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import pub.moskwin.core.ClientCredentials
import pub.moskwin.core.Kinopub

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kinopub = Kinopub(
            ClientCredentials(BuildConfig.CLIENT_ID, BuildConfig.CLIENT_SECRET),
            PrefsTokenStore(applicationContext),
        )
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Background, surface = Background)) {
                // Без Surface цвет текста по умолчанию чёрный — на тёмном фоне его не видно.
                CompositionLocalProvider(LocalContentColor provides Foreground) {
                    Box(Modifier.fillMaxSize().background(Background), contentAlignment = Alignment.Center) {
                        App(kinopub, resumes)
                    }
                }
            }
        }
    }

    /** Счётчик возвратов в приложение: по нему главный экран обновляет данные. */
    private var resumes by mutableIntStateOf(0)

    override fun onResume() {
        super.onResume()
        resumes++
    }
}

val Background = Color(0xFF141414)
val Foreground = Color(0xFFEDEDED)

@Composable
private fun App(kinopub: Kinopub, resumes: Int) {
    var loggedIn by remember { mutableStateOf(kinopub.auth.isLoggedIn) }
    when {
        BuildConfig.CLIENT_SECRET.isEmpty() ->
            Text("Ключ клиента не задан: добавьте kinopub.clientId и kinopub.clientSecret в local.properties")
        !loggedIn -> LoginScreen(kinopub.auth, onLoggedIn = { loggedIn = true })
        else -> {
            LaunchedEffect(Unit) {
                // Название устройства в списке на сайте; сбой здесь входу не мешает.
                runCatching {
                    kinopub.notifyDevice("moskwin-pub", Build.MODEL, "Android ${Build.VERSION.RELEASE}")
                }
            }
            // Сюда попадаем, когда сервер отклонил токены: они уже стёрты, нужен новый вход.
            HomeScreen(kinopub, refreshKey = resumes, onLoginRequired = { loggedIn = false })
        }
    }
}
