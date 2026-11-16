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

/**
 * Обновление приложения из репозитория. При запуске читает `apk/version.json`; если там
 * версия новее установленной, у «Настроек» в меню загорается красная точка, а в самих
 * настройках появляется строка с новой версией: по ней открывается список изменений и
 * кнопка установки. APK скачивается, и открывается системное окно установки: подтвердить её должен человек,
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

    /** Идёт проверка новой версии. */
    var checking by mutableStateOf(false)
        private set

    /** Почему последняя проверка не удалась; null — удалась или ещё не шла. */
    var checkProblem by mutableStateOf<String?>(null)
        private set

    /** Проверяет, вышла ли новая версия. Итог — в [available], [checking] и [checkProblem]. */
    suspend fun check() {
        if (checking) return
        checking = true
        try {
            val latest = fetchAppVersion(VERSION_URL)
            available = latest.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
            checkProblem = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            checkProblem = e.message ?: e.javaClass.simpleName
        } finally {
            checking = false
        }
    }

    /** Скачивает новую версию и открывает системное окно установки. */
    suspend fun install() {
        if (progress != null) return
        problem = null
        progress = 0f
        try {
            // Описание версии читается заново: пока приложение было открыто, могла выйти
            // следующая, и файл прежней с сервера уже удалён. Не вышло прочитать — берём
            // то, что знали.
            val latest = runCatching { fetchAppVersion(VERSION_URL) }.getOrNull()
                ?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
                ?.also { available = it }
                ?: available ?: return
            // Имя файла — из описания версии: в нём стоит её номер. Прошлые загрузки удаляются.
            val name = latest.apkName
            val folder = File(context.cacheDir, "updates")
            folder.listFiles()?.forEach { if (it.name != name) it.delete() }
            val file = File(folder, name)
            downloadFile("$BASE/$name", file) { progress = it }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    // CLEAR_TASK обязателен: системный установщик держит своё окно от прошлой
                    // установки («Приложение установлено — Открыть»), и без этого флага вместо
                    // нового запроса показывал его. Имя файла с версией меняет и адрес запроса.
                    .addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
                    ),
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
        // Адреса зашиты в установленные приложения: имя репозитория, ветки, папки и файла версии менять нельзя.
        const val BASE = "https://raw.githubusercontent.com/UserNameMoskwin/kino-pub-modern/main/apk"
        const val VERSION_URL = "$BASE/version.json"
    }
}
