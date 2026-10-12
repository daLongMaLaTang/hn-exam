$ErrorActionPreference = 'Stop'

$docker = 'C:\Program Files\Docker\Docker\resources\bin\docker.exe'
$envFile = Join-Path $PSScriptRoot '.env.local'
if (-not (Test-Path $docker)) { throw 'Docker Desktop 未安装。' }
if (-not (Test-Path $envFile)) { throw '本机环境文件不存在，请先运行 init-local-infrastructure.ps1。' }

$serverVersion = & $docker info --format '{{.ServerVersion}}'
$redisStatus = & $docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' hnexam-redis
$portBinding = & $docker port hnexam-redis 6379/tcp

[pscustomobject]@{
    DockerEngine = $serverVersion
    RedisContainer = $redisStatus
    RedisAddress = $portBinding
}
