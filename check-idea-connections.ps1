$ErrorActionPreference = 'Stop'
$java = 'E:\web\jdk17\jdk-17.0.20.1+1\bin\java.exe'
$driver = 'D:\web\maven-repo\com\h2database\h2\2.3.232\h2-2.3.232.jar'
$localProfile = Get-Content -LiteralPath (Join-Path $PSScriptRoot '.env.local') | Where-Object { $_ -match '^spring\.profiles\.active\s*=' } | Select-Object -Last 1
if ($localProfile -and (($localProfile -split '=', 2)[1].Trim() -split ',') -contains 'mysql') {
    $driver = Join-Path $PSScriptRoot '.local/idea-drivers/mysql-connector-j-9.7.0.jar'
    & $java '-cp' $driver (Join-Path $PSScriptRoot 'deploy/CheckLocalDatabase.java') 'mysql' (Join-Path $PSScriptRoot '.local/mysql8/app-client.ini')
    if ($LASTEXITCODE -ne 0) { throw 'MySQL连接失败，请先运行 start-local-mysql.ps1。' }
} else {
$database = (Join-Path $PSScriptRoot 'backend/data/hnexam').Replace('\', '/')
$url = "jdbc:h2:file:${database};MODE=MySQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=TRUE;IFEXISTS=TRUE"
& $java '-Dh2.bindAddress=127.0.0.1' '-cp' $driver (Join-Path $PSScriptRoot 'deploy/CheckLocalDatabase.java') $url
if ($LASTEXITCODE -ne 0) { throw 'H2连接失败，请确认后台已使用AUTO_SERVER配置重启。' }
}
& docker exec hnexam-redis sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --no-auth-warning ping'
if ($LASTEXITCODE -ne 0) { throw 'Redis连接失败，请先启动本地基础设施。' }
$health = Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/health' -TimeoutSec 10
if ($health.status -ne 'UP' -or $health.redis -ne 'UP') { throw '后台或Redis尚未就绪。' }
$health | Select-Object status, redis
