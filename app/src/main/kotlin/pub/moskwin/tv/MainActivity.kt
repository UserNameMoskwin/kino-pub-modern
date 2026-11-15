package pub.moskwin.tv

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
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
                Box(Modifier.fillMaxSize().background(Background), contentAlignment = Alignment.Center) {
                    App(kinopub)
                }
            }
        }
    }
}

val Background = Color(0xFF141414)

@Composable
private fun App(kinopub: Kinopub) {
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
            HomeStub(onLogout = {
                kinopub.auth.logoutLocally()
                loggedIn = false
            })
        }
    }
}

/** Заглушка до шага 2 (главный экран). */
@Composable
private fun HomeStub(onLogout: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text("Вход выполнен", style = MaterialTheme.typography.headlineMedium)
        Button(onClick = onLogout) { Text("Выйти") }
    }
}
