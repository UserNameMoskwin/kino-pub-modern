# Готовит выпуск: собирает оптимизированную сборку и кладёт её в apk/kino-pub.apk.
# Перед запуском нужно увеличить versionCode и versionName в apk/version.json — иначе
# установленные приложения не увидят новую версию. После запуска остаётся закоммитить
# apk/ и отправить в ветку main: с неё приложения берут обновление.
$ErrorActionPreference = 'Stop'
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = (Get-ChildItem "$env:USERPROFILE\.jdks" -Directory -Filter 'jdk-21*' | Select-Object -First 1).FullName }
$project = Split-Path $PSScriptRoot -Parent

& "$project\gradlew.bat" -p $project ':core:test' ':app:assembleRelease' --console=plain -q
if ($LASTEXITCODE -ne 0) { throw 'Сборка или тесты не прошли' }

Copy-Item "$project\app\build\outputs\apk\release\app-release.apk" "$project\apk\kino-pub.apk" -Force
$version = Get-Content "$project\apk\version.json" -Raw | ConvertFrom-Json
"Готово: apk/kino-pub.apk, версия $($version.versionName) (код $($version.versionCode))"
