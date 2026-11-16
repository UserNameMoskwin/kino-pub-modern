# Собирает оптимизированную сборку и ставит её на телевизор.
# После установки код приложения компилируется заранее (иначе первые минуты
# интерфейс работает через интерпретатор и заметно тормозит).
#   -Debug  ставит отладочную сборку: нужна, чтобы читать токен через run-as.
param([string]$Tv = '192.168.31.229:5555', [switch]$Debug)

$ErrorActionPreference = 'Stop'
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = (Get-ChildItem "$env:USERPROFILE\.jdks" -Directory -Filter 'jdk-21*' | Select-Object -First 1).FullName }
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$project = Split-Path $PSScriptRoot -Parent
$variant = if ($Debug) { 'debug' } else { 'release' }

$source = "$project\source"
& "$source\gradlew.bat" -p $source ":app:assemble$variant" --console=plain -q
if ($LASTEXITCODE -ne 0) { throw 'Сборка не удалась' }

& $adb connect $Tv | Out-Null
& $adb -s $Tv install -r "$source\app\build\outputs\apk\$variant\app-$variant.apk"
if ($LASTEXITCODE -ne 0) { throw 'Установка не удалась' }
& $adb -s $Tv shell cmd package compile -m speed -f pub.moskwin.tv
