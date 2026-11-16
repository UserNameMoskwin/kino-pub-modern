package pub.moskwin.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pub.moskwin.core.Device
import pub.moskwin.core.Kinopub
import pub.moskwin.core.LoginRequiredException
import pub.moskwin.core.User
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** За сколько дней до конца подписки приложение начинает о ней напоминать. */
internal const val SUBSCRIPTION_WARN_DAYS = 7

/** Профиль: аккаунт, подписка, это устройство и выход из аккаунта на телевизоре. */
@Composable
internal fun ProfileScreen(kinopub: Kinopub, settings: AppSettings, onLoggedOut: () -> Unit) {
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf<User?>(null) }
    var device by remember { mutableStateOf<Device?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    // Отвязка — в два нажатия: первое только предупреждает.
    var confirmUnlink by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            withContext(Dispatchers.Default) {
                user = kinopub.user()
                device = runCatching { kinopub.device() }.getOrNull()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LoginRequiredException) {
            onLoggedOut()
        } catch (e: Exception) {
            status = "Профиль не загрузился: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 28.dp)) {
        Text("Профиль", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = Edge, end = Edge, bottom = 16.dp))
        val u = user
        // Подписка кончается: напоминание стоит первым. Продлить её можно только на сайте.
        if (u != null && u.subscription.endsWithin(SUBSCRIPTION_WARN_DAYS)) {
            Row(
                Modifier.padding(start = Edge, end = Edge, bottom = 16.dp).fillMaxWidth(0.8f).glassPanel().padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(10.dp).background(Accent, CircleShape))
                Column(Modifier.padding(start = 14.dp)) {
                    Text(
                        if (u.subscription.active) "До окончания подписки осталось меньше $SUBSCRIPTION_WARN_DAYS дней"
                        else "Подписка не активна",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text("Продлите её на сайте kino.watch", color = Dim, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        PanelRow("Аккаунт", u?.username ?: "…", focusFirst = true) {}
        PanelRow(
            "Подписка",
            when {
                u == null -> "…"
                !u.subscription.active -> "не активна"
                else -> "до ${date(u.subscription.endTime)} · осталось дней: ${u.subscription.days.toInt()}"
            },
        ) {}
        PanelRow("На сервисе с", u?.regDate?.takeIf { it > 0 }?.let(::date) ?: "…") {}
        PanelRow("Это устройство", settings.deviceName + (device?.id?.let { " · ID $it" } ?: "")) {}
        PanelRow(
            if (confirmUnlink) "Нажмите ещё раз, чтобы отвязать телевизор" else "Выйти из аккаунта на этом телевизоре",
            if (confirmUnlink) "Потом понадобится снова ввести код на сайте" else null,
        ) {
            if (!confirmUnlink) {
                confirmUnlink = true
            } else scope.launch {
                try {
                    kinopub.unlinkDevice()
                    onLoggedOut()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    confirmUnlink = false
                    status = "Не получилось отвязать: ${e.message}"
                }
            }
        }
        status?.let { Text(it, color = Dim, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = Edge, vertical = 16.dp)) }
    }
}

private fun date(seconds: Long): String = SimpleDateFormat("d MMMM yyyy", Locale("ru")).format(Date(seconds * 1000))
