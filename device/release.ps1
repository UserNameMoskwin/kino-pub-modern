# Готовит выпуск: собирает оптимизированную сборку и кладёт её в apk/ под именем с номером
# версии — apk/kino-pub-<версия>.apk. Прежний файл с версией удаляется, имя нового
# записывается в apk/version.json (поле file) и в ссылку на скачивание в README.md.
#
# Перед запуском нужно увеличить versionCode и versionName в apk/version.json и описать
# изменения в notes — иначе установленные приложения не увидят новую версию. После
# запуска остаётся закоммитить изменения и отправить в ветку main: с неё приложения
# берут обновление.
#
# apk/kino-pub.apk скрипт не трогает: это мост для приложений версий 0.2–0.4, см.
# docs/development.md.
$ErrorActionPreference = 'Stop'
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = (Get-ChildItem "$env:USERPROFILE\.jdks" -Directory -Filter 'jdk-21*' | Select-Object -First 1).FullName }
$project = Split-Path $PSScriptRoot -Parent
$utf8 = New-Object System.Text.UTF8Encoding $false

$versionPath = "$project\apk\version.json"
$version = [IO.File]::ReadAllText($versionPath, $utf8) | ConvertFrom-Json
$file = "kino-pub-$($version.versionName).apk"

# Имя файла записывается до сборки: version.json читает и Gradle.
$version | Add-Member -NotePropertyName file -NotePropertyValue $file -Force
[IO.File]::WriteAllText($versionPath, (($version | ConvertTo-Json) -replace "`r`n", "`n") + "`n", $utf8)

$source = "$project\source"
& "$source\gradlew.bat" -p $source ':core:test' ':app:assembleRelease' --console=plain -q
if ($LASTEXITCODE -ne 0) { throw 'Сборка или тесты не прошли' }

Get-ChildItem "$project\apk" -Filter 'kino-pub-*.apk' | Remove-Item
Copy-Item "$source\app\build\outputs\apk\release\app-release.apk" "$project\apk\$file" -Force

$readmePath = "$project\README.md"
$readme = [IO.File]::ReadAllText($readmePath, $utf8)
$megabytes = [math]::Round((Get-Item "$project\apk\$file").Length / 1MB, 1).ToString([Globalization.CultureInfo]::GetCultureInfo('ru-RU'))
$link = "## [⬇ Скачать приложение, версия $($version.versionName) (APK, $megabytes МБ)](https://github.com/UserNameMoskwin/kino-pub-modern/raw/main/apk/$file)"
$updated = [regex]::Replace($readme, '(?m)^## \[⬇ Скачать приложение[^\r\n]*', { $link })
if ($updated -eq $readme -and $readme -notmatch [regex]::Escape($link)) { throw 'В README.md не нашлась строка со ссылкой на скачивание' }
[IO.File]::WriteAllText($readmePath, $updated, $utf8)

"Готово: apk/$file, версия $($version.versionName) (код $($version.versionCode))"
