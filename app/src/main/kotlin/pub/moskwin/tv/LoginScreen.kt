package pub.moskwin.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.CancellationException
import pub.moskwin.core.Auth

private sealed interface LoginState {
    data object Loading : LoginState
    data class Code(val userCode: String, val uri: String) : LoginState
    data class Failed(val message: String) : LoginState
}

/** Вход по коду: показывает код и адрес, опрашивает сервер, истёкший код заменяет новым. */
@Composable
fun LoginScreen(auth: Auth, onLoggedIn: () -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<LoginState>(LoginState.Loading) }

    LaunchedEffect(attempt) {
        state = LoginState.Loading
        try {
            while (true) {
                val code = auth.requestDeviceCode()
                state = LoginState.Code(code.userCode, code.verificationUri)
                if (auth.awaitLogin(code)) {
                    onLoggedIn()
                    return@LaunchedEffect
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state = LoginState.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
        when (val s = state) {
            LoginState.Loading -> Text("Получаю код…", style = MaterialTheme.typography.titleLarge)
            is LoginState.Code -> {
                Text("Откройте ${s.uri} и введите код", style = MaterialTheme.typography.titleLarge)
                Text(s.userCode, fontSize = 96.sp, fontWeight = FontWeight.Bold, letterSpacing = 12.sp)
                Text("Экран обновится сам после ввода", style = MaterialTheme.typography.bodyLarge)
            }
            is LoginState.Failed -> {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                Text("Не получилось войти", style = MaterialTheme.typography.titleLarge)
                Text(s.message, style = MaterialTheme.typography.bodyLarge)
                Button(onClick = { attempt++ }, modifier = Modifier.focusRequester(focus)) { Text("Повторить") }
            }
        }
    }
}
