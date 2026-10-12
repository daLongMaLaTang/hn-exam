$ErrorActionPreference = 'Stop'

$projectRoot = $PSScriptRoot
$envFile = Join-Path $projectRoot '.env.local'
$composeFile = Join-Path $projectRoot 'deploy\docker-compose.redis.yml'
$docker = 'C:\Program Files\Docker\Docker\resources\bin\docker.exe'
$dockerDesktop = 'C:\Program Files\Docker\Docker\Docker Desktop.exe'

if (-not (Test-Path $docker)) {
    throw 'Docker Desktop 4.93 or later was not found.'
}

function New-SecureValue([int]$byteCount) {
    $bytes = [byte[]]::new($byteCount)
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function Read-LocalEnvironment([string]$path) {
    $values = [ordered]@{}
    if (Test-Path $path) {
        foreach ($line in [IO.File]::ReadAllLines($path)) {
            if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith('#')) { continue }
            $parts = $line.Split('=', 2)
            if ($parts.Count -eq 2) { $values[$parts[0].Trim()] = $parts[1].Trim() }
        }
    }
    return $values
}

$values = Read-LocalEnvironment $envFile
if ($values.Contains('spring.profiles.active') -and ($values['spring.profiles.active'] -split ',') -contains 'mysql') {
    & (Join-Path $projectRoot 'start-local-mysql.ps1')
}
if (-not $values.Contains('HNEXAM_REDIS_PASSWORD') -or [string]::IsNullOrWhiteSpace($values.HNEXAM_REDIS_PASSWORD)) {
    $values.HNEXAM_REDIS_PASSWORD = New-SecureValue 32
}
if (-not $values.Contains('HNEXAM_JWT_SECRET') -or [string]::IsNullOrWhiteSpace($values.HNEXAM_JWT_SECRET)) {
    $values.HNEXAM_JWT_SECRET = New-SecureValue 48
}
$values.HNEXAM_REDIS_ENABLED = 'true'
$values.HNEXAM_REDIS_HOST = '127.0.0.1'
$values.HNEXAM_REDIS_PORT = '6379'

# Keep unrelated settings and comments (including optional AI/database settings).
$managedKeys = @('HNEXAM_REDIS_ENABLED', 'HNEXAM_REDIS_HOST', 'HNEXAM_REDIS_PORT', 'HNEXAM_REDIS_PASSWORD', 'HNEXAM_JWT_SECRET')
$lines = [Collections.Generic.List[string]]::new()
$seen = @{}
if (Test-Path $envFile) {
    foreach ($line in [IO.File]::ReadAllLines($envFile)) {
        $parts = $line.Split('=', 2)
        $key = $parts[0].Trim()
        if ($parts.Count -eq 2 -and $managedKeys -contains $key) {
            $lines.Add("$key=$($values[$key])")
            $seen[$key] = $true
        } else {
            $lines.Add($line)
        }
    }
}
foreach ($key in $managedKeys) {
    if (-not $seen.ContainsKey($key)) { $lines.Add("$key=$($values[$key])") }
}
$newContent = ($lines -join [Environment]::NewLine) + [Environment]::NewLine
if (-not (Test-Path $envFile) -or [IO.File]::ReadAllText($envFile) -cne $newContent) {
    [IO.File]::WriteAllText($envFile, $newContent, [Text.UTF8Encoding]::new($false))
}

$serverReady = $false
try {
    & $docker info --format '{{.ServerVersion}}' 2>$null | Out-Null
    $serverReady = $LASTEXITCODE -eq 0
} catch {}

if (-not $serverReady) {
    if (-not (Test-Path $dockerDesktop)) { throw 'The Docker Desktop installation is incomplete.' }
    Start-Process $dockerDesktop -WindowStyle Hidden
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        Start-Sleep -Seconds 1
        try {
            & $docker info --format '{{.ServerVersion}}' 2>$null | Out-Null
            if ($LASTEXITCODE -eq 0) { $serverReady = $true; break }
        } catch {}
    }
}
if (-not $serverReady) { throw 'The Docker engine did not start within 60 seconds.' }

$env:HNEXAM_REDIS_PASSWORD = $values.HNEXAM_REDIS_PASSWORD
& $docker compose --env-file $envFile -f $composeFile up -d
if ($LASTEXITCODE -ne 0) { throw 'The Redis container failed to start.' }

$healthy = $false
for ($attempt = 0; $attempt -lt 45; $attempt++) {
    $status = & $docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' hnexam-redis 2>$null
    if ($status -eq 'healthy') { $healthy = $true; break }
    if ($status -eq 'unhealthy' -or $status -eq 'exited') { break }
    Start-Sleep -Seconds 1
}
if (-not $healthy) {
    & $docker logs --tail 30 hnexam-redis
    throw 'The Redis container health check failed.'
}

& $docker exec -e "REDISCLI_AUTH=$($values.HNEXAM_REDIS_PASSWORD)" hnexam-redis redis-cli ping | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'The Redis connection check failed.' }

Write-Host 'Docker and Redis are ready (Redis listens on 127.0.0.1:6379 only).' -ForegroundColor Green
