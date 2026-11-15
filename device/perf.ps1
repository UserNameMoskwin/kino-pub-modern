# Замер скорости интерфейса на телевизоре: холодный старт и проход пультом по главному
# экрану, карточке и обратно. Печатает метрики приложения (тег moskwin-perf) и сводку
# системы по кадрам (dumpsys gfxinfo). Ничего не меняет в аккаунте.
param([string]$Tv = '192.168.31.229:5555')

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$pkg = 'pub.moskwin.tv'
function Keys([int[]]$codes, [double]$pause = 0.35) {
    & $adb -s $Tv shell (($codes | ForEach-Object { "input keyevent $_; sleep $pause" }) -join '; ')
}

& $adb connect $Tv | Out-Null
$front = & $adb -s $Tv shell "dumpsys activity activities | grep mResumedActivity"
if ($front -notmatch 'pub\.moskwin\.tv|launcher') { throw "На экране другое приложение: $front" }

& $adb -s $Tv shell am force-stop $pkg
& $adb -s $Tv logcat -c
& $adb -s $Tv shell monkey -p $pkg -c android.intent.category.LEANBACK_LAUNCHER 1 | Out-Null
Start-Sleep 12
& $adb -s $Tv shell dumpsys gfxinfo $pkg reset | Out-Null

$down = 20; $up = 19; $left = 21; $right = 22; $ok = 23; $back = 4
Keys (@($right) * 2 + @($left) * 2)                    # ряд «Продолжить»
Keys (@($down) * 4)                                    # вниз по рядам
Keys (@($right) * 12)                                  # вдоль ряда новинок
Keys (@($left) * 6)
Keys (@($up) * 4)                                      # обратно наверх
Keys @($ok) 5                                          # карточка
Keys (@($down) * 2 + @($right) * 6 + @($left) * 3)     # серии
Keys @($back) 3
Keys (@($down) * 3 + @($up) * 3)

'--- moskwin-perf'
& $adb -s $Tv logcat -d -s moskwin-perf:I | Where-Object { $_ -match 'moskwin-perf' -and $_ -notmatch ' image | http ' } |
    ForEach-Object { $_ -replace '^.*moskwin-perf: ', '' }
$log = & $adb -s $Tv logcat -d -s moskwin-perf:I
$images = $log | Where-Object { $_ -match ' image (\d+)ms (\w+)' } | ForEach-Object { [pscustomobject]@{ ms = [int]$Matches[1]; src = $Matches[2] } }
$images | Group-Object src | ForEach-Object {
    $m = $_.Group.ms | Sort-Object
    "images $($_.Name): n=$($m.Count) p50=$($m[[int](($m.Count - 1) * 0.5)])ms max=$($m[-1])ms"
}
$http = $log | Where-Object { $_ -match ' http (\d+)ms' } | ForEach-Object { [int]$Matches[1] } | Sort-Object
if ($http) { "http: n=$($http.Count) p50=$($http[[int](($http.Count - 1) * 0.5)])ms max=$($http[-1])ms" }
'--- gfxinfo'
& $adb -s $Tv shell "dumpsys gfxinfo $pkg | grep -E 'Total frames|Janky frames|percentile|Number Slow UI|Number Slow bitmap|Number Slow issue'"
& $adb -s $Tv shell "dumpsys meminfo $pkg | grep -E 'TOTAL PSS'"
