package pub.moskwin.tv

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import pub.moskwin.core.AppVersion
import pub.moskwin.core.downloadFile
import pub.moskwin.core.fetchAppVersion
import java.io.File
import kotlin.math.roundToInt

/**
 * Обновление приложения из репозитория. При запуске читает `apk/version.json`; если там
 * версия новее установленной, в меню появляется пункт «Обновить». По нажатию APK
 * скачивается и открывается системное окно установки: подтвердить её должен человек,
 * без подтверждения Android обновляет только приложения из магазинов.
 * Обновление встаёт поверх, только если подписано тем же ключом, что и установленная сборка.
 */
internal class Updater(private val context: Context) {
    /** Версия новее установленной; null — обновлять нечего или проверка ещё не прошла. */
    var available by mutableStateOf<AppVersion?>(null)
        private set

    /** Доля скачанного, пока идёт загрузка. */
    var progress by mutableStateOf<Float?>(null)
        private set

    var problem by mutableStateOf<String?>(null)
        private set

    /** Подпись пункта меню; null — пункта нет. */
    val label: String?
        get() {
            val version = available ?: return null
            val loaded = progress
            return when {
                loaded != null -> "Загрузка ${(loaded * 100).roundToInt()} %"
                problem != null -> "Не вышло, повторить"
                else -> "Обновить до ${version.versionName}"
            }
        }

    /** Проверяет, вышла ли новая версия. Возвращает текст итога для экрана настроек. */
    suspend fun check(): String = try {
        val latest = fetchAppVersion(VERSION_URL)
        if (latest.versionCode > BuildConfig.VERSION_CODE) {
            available = latest
            "Доступна версия ${latest.versionName}: пункт «Обновить» в меню"
        } else {
            available = null
            "Установлена последняя версия"
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        "Проверить обновления не удалось: ${e.message ?: e.javaClass.simpleName}"
    }

    /** Скачивает новую версию и открывает системное окно установки. */
    suspend fun install() {
        if (progress != null) return
        problem = null
        progress = 0f
        try {
            val file = File(File(context.cacheDir, "updates"), "kino-pub.apk")
            downloadFile(APK_URL, file) { progress = it }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            problem = e.message ?: e.javaClass.simpleName
            Perf.event("update", 0, "failed: $problem")
        } finally {
            progress = null
        }
    }

    private companion object {
        // Адреса зашиты в установленные приложения: имя репозитория, ветки и файлов менять нельзя.
        const val BASE = "https://raw.githubusercontent.com/UserNameMoskwin/kino-pub-modern/main/apk"
        const val VERSION_URL = "$BASE/version.json"
        const val APK_URL = "$BASE/kino-pub.apk"
    }
}
