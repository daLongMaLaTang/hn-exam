$ErrorActionPreference = 'Stop'

$javaHome = 'E:\web\jdk17\jdk-17.0.20.1+1'
$mavenBin = 'D:\web\maven\bin'
$nodeBin = 'D:\web\nodejs'
$logDir = 'E:\hnExam\logs'

if (-not (Test-Path "$javaHome\bin\java.exe")) { throw 'Java 17 not found.' }
if (-not (Test-Path "$mavenBin\mvn.cmd")) { throw 'Maven not found.' }
if (-not (Test-Path "$nodeBin\npm.cmd")) { throw 'Node.js not found.' }
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir | Out-Null }

# 确保 Docker/Redis 已就绪，并生成仅供本机使用的随机密码与 JWT 密钥。
& 'E:\hnExam\init-local-infrastructure.ps1'

$backendCommand = "`$env:JAVA_HOME='$javaHome'; `$env:Path='${javaHome}\bin;${mavenBin};'+`$env:Path; Set-Location 'E:\hnExam\backend'; & '${mavenBin}\mvn.cmd' '-Dmaven.repo.local=D:\web\maven-repo' '-Dspring-boot.run.jvmArguments=-Dh2.bindAddress=127.0.0.1' spring-boot:run"
$frontendCommand = "Set-Location 'E:\hnExam\frontend'; & '${nodeBin}\npm.cmd' run dev"

Start-Process powershell -WindowStyle Hidden -ArgumentList '-NoProfile', '-Command', $backendCommand -RedirectStandardOutput "$logDir\backend.log" -RedirectStandardError "$logDir\backend-error.log"
Start-Process powershell -WindowStyle Hidden -ArgumentList '-NoProfile', '-Command', $frontendCommand -RedirectStandardOutput "$logDir\frontend.log" -RedirectStandardError "$logDir\frontend-error.log"

$backendReady = $false
$frontendReady = $false
for ($attempt = 0; $attempt -lt 40; $attempt++) {
    try { $backendReady = (Invoke-WebRequest 'http://127.0.0.1:8080/api/health' -UseBasicParsing -TimeoutSec 1).StatusCode -eq 200 } catch {}
    try { $frontendReady = (Invoke-WebRequest 'http://127.0.0.1:5173' -UseBasicParsing -TimeoutSec 1).StatusCode -eq 200 } catch {}
    if ($backendReady -and $frontendReady) { break }
    Start-Sleep -Milliseconds 500
}
if (-not $backendReady -or -not $frontendReady) { throw "启动未完成，请查看 $logDir 下的日志。后端=$backendReady 前端=$frontendReady" }
Write-Host 'hnExam 已启动：http://127.0.0.1:5173（Redis 已启用）' -ForegroundColor Green
