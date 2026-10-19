[CmdletBinding()]
param(
    [switch]$LocalOnly,
    [switch]$SkipBuild,
    [switch]$Stop,
    [string]$JavaHome = 'E:\web\jdk17\jdk-17.0.20.1+1',
    [string]$Maven = 'D:\web\maven\bin\mvn.cmd',
    [string]$MavenRepository = 'D:\web\maven-repo',
    [string]$Node = 'D:\web\nodejs\node.exe',
    [string]$Cloudflared = 'C:\Program Files (x86)\cloudflared\cloudflared.exe'
)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$local = Join-Path $root '.local'
$logs = Join-Path $root 'logs'
$statePath = Join-Path $local 'cloudflare-deployment.json'
$urlPath = Join-Path $local 'cloudflare-url.txt'
$java = Join-Path $JavaHome 'bin\java.exe'
$npm = Join-Path (Split-Path $Node) 'npm.cmd'
$server = Join-Path $root 'deploy\serve-built-frontend.mjs'
$state = @{ backend = $null; frontend = $null; tunnel = $null; publicUrl = ''; release = ''; status = 'starting' }
New-Item -ItemType Directory -Path $local,$logs -Force | Out-Null

function Save-State {
    $state.updatedAt = [DateTimeOffset]::Now.ToString('o')
    $temp = "$statePath.tmp"
    [IO.File]::WriteAllText($temp, ($state | ConvertTo-Json -Depth 6), [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $temp -Destination $statePath -Force
}
function Get-OwnedProcess($record) {
    if (!$record) { return $null }
    $process = Get-Process -Id ([int]$record.pid) -ErrorAction SilentlyContinue
    if (!$process) { return $null }
    $recordedStart = ([DateTime]$record.started).ToUniversalTime()
    if ($process.StartTime.ToUniversalTime().Ticks -ne $recordedStart.Ticks -or $process.Path -ne $record.executable) {
        throw "Saved PID $($record.pid) belongs to a different process; no process was stopped."
    }
    return $process
}
function Stop-OwnedProcess([string]$name) {
    $process = Get-OwnedProcess $state[$name]
    if ($process) {
        Stop-Process -Id $process.Id
        if (!$process.WaitForExit(15000)) { throw "Process $name did not exit." }
    }
    $state[$name] = $null
    Save-State
}
function Start-OwnedProcess([string]$name, [string]$executable, [string[]]$arguments, [string]$directory) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
    $stdout = Join-Path $logs "$name-cloudflare-$stamp.log"
    $stderr = Join-Path $logs "$name-cloudflare-$stamp-error.log"
    $process = Start-Process -FilePath $executable -ArgumentList $arguments -WorkingDirectory $directory -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
    $state[$name] = @{ pid = $process.Id; started = $process.StartTime.ToUniversalTime().ToString('o'); executable = (Resolve-Path -LiteralPath $executable).Path; stdout = $stdout; stderr = $stderr }
    Save-State
}
function Wait-Health([string]$url, [int]$seconds = 90) {
    $deadline = (Get-Date).AddSeconds($seconds)
    do {
        try {
            $result = Invoke-RestMethod -Uri $url -TimeoutSec 5
            if ($result.status -eq 'UP' -and $result.redis -eq 'UP') { return }
        } catch { }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    throw "Health check failed: $url. See $logs."
}
function Assert-PortOwned([int]$port, [string]$name) {
    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)
    $owned = Get-OwnedProcess $state[$name]
    foreach ($listener in $listeners) {
        if (!$owned -or $listener.OwningProcess -ne $owned.Id) {
            throw "Port $port is occupied by an unmanaged process. Stop that service yourself before deploying."
        }
    }
}

$lock = $null
$oldJavaHome = $env:JAVA_HOME
$oldPath = $env:Path
$oldDist = $env:HNEXAM_FRONTEND_DIST
$oldPublicUrl = $env:HNEXAM_PUBLIC_URL
$oldPort = $env:HNEXAM_FRONTEND_PORT
$newTunnel = $false
try {
    try { $lock = [IO.File]::Open((Join-Path $local 'cloudflare-deploy.lock'), 'OpenOrCreate', 'ReadWrite', 'None') }
    catch { throw 'Another deployment is running. Wait for it to finish.' }
    if (Test-Path -LiteralPath $statePath) {
        $saved = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
        foreach ($key in @('backend','frontend','tunnel','publicUrl','release','status')) {
            if ($saved.PSObject.Properties.Name -contains $key) { $state[$key] = $saved.$key }
        }
    }
    if ($Stop) {
        foreach ($name in @('tunnel','frontend','backend')) { Stop-OwnedProcess $name }
        $state.publicUrl = ''; $state.status = 'stopped'; Save-State
        [IO.File]::WriteAllText($urlPath, '')
        Write-Host 'Application and tunnel stopped. MySQL and Redis were left running.'
        return
    }
    foreach ($file in @($java,$Node,$npm,$server,(Join-Path $root '.env.local'))) {
        if (!(Test-Path -LiteralPath $file -PathType Leaf)) { throw "Required file is missing: $file" }
    }
    if (!$LocalOnly -and !(Test-Path -LiteralPath $Cloudflared -PathType Leaf)) { throw 'cloudflared is not installed.' }
    $settings = @{}
    foreach ($line in [IO.File]::ReadAllLines((Join-Path $root '.env.local'))) {
        if ($line -match '^\s*([^#=\s]+)\s*=(.*)$') { $settings[$matches[1]] = $matches[2].Trim() }
    }
    if (($settings['spring.profiles.active'] -split ',') -notcontains 'mysql') { throw 'The existing MySQL profile is required; H2 fallback is not allowed.' }
    foreach ($key in @('HNEXAM_DB_PASSWORD','HNEXAM_REDIS_PASSWORD','HNEXAM_JWT_SECRET')) {
        if ([string]::IsNullOrWhiteSpace($settings[$key])) { throw "Existing local setting is missing: $key. No credentials were generated." }
    }
    foreach ($file in @('.local\mysql8\data\auto.cnf','.local\ai-master.key')) {
        if (!(Test-Path -LiteralPath (Join-Path $root $file))) { throw "Existing data/key is missing: $file. Initialization is not allowed." }
    }
    Assert-PortOwned 8080 'backend'
    Assert-PortOwned 5173 'frontend'
    # Validate PID identity before building or changing running services.
    $existingTunnel = Get-OwnedProcess $state.tunnel
    if ($LocalOnly -and $existingTunnel) { throw 'A public tunnel is running. Use -Stop before switching to local-only mode.' }
    $env:JAVA_HOME = $JavaHome
    $env:Path = "$(Join-Path $JavaHome 'bin');$(Split-Path $Node);$env:Path"
    if (!$SkipBuild) {
        if (!(Test-Path -LiteralPath $Maven)) { throw 'Maven is missing.' }
        Push-Location (Join-Path $root 'frontend')
        try {
            if (!(Test-Path 'node_modules')) {
                & $npm ci
                if ($LASTEXITCODE -ne 0) { throw 'npm ci failed.' }
            }
            & $npm run build
            if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed; the running release was not replaced.' }
        } finally { Pop-Location }
        Push-Location (Join-Path $root 'backend')
        try {
            & $Maven "-Dmaven.repo.local=$MavenRepository" -DskipTests package
            if ($LASTEXITCODE -ne 0) { throw 'Backend build failed; the running release was not replaced.' }
        } finally { Pop-Location }
    }
    $jar = Join-Path $root 'backend\target\hn-exam-0.1.0-SNAPSHOT.jar'
    $dist = Join-Path $root 'frontend\dist'
    if (!(Test-Path $jar) -or !(Test-Path (Join-Path $dist 'index.html'))) { throw 'Built artifacts are missing.' }
    & (Join-Path $root 'init-local-infrastructure.ps1')
    # Keep immutable release copies; do not overwrite files used by a running release.
    $release = Join-Path $local ('releases\' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
    New-Item -ItemType Directory -Path $release -Force | Out-Null
    Copy-Item -LiteralPath $jar -Destination (Join-Path $release 'app.jar')
    Copy-Item -LiteralPath $dist -Destination (Join-Path $release 'frontend') -Recurse
    $publicUrl = ''
    if (!$LocalOnly) {
        if ($existingTunnel -and $state.publicUrl -match '^https://[a-z0-9-]+\.trycloudflare\.com$') {
            $publicUrl = $state.publicUrl
        } else {
            if ($existingTunnel) { throw 'Existing tunnel has no valid saved URL; run -Stop first.' }
            Start-OwnedProcess 'tunnel' $Cloudflared @('tunnel','--no-autoupdate','--url','http://127.0.0.1:5173') $root
            $newTunnel = $true
            $deadline = (Get-Date).AddSeconds(90)
            do {
                $tunnel = Get-OwnedProcess $state.tunnel
                if (!$tunnel) { throw "Cloudflare tunnel exited. See $($state.tunnel.stderr)." }
                $text = [IO.File]::ReadAllText($state.tunnel.stderr) + [IO.File]::ReadAllText($state.tunnel.stdout)
                if ($text -match 'https://[a-z0-9-]+\.trycloudflare\.com') { $publicUrl = $matches[0]; break }
                Start-Sleep -Seconds 1
            } while ((Get-Date) -lt $deadline)
            if (!$publicUrl) { throw 'Cloudflare did not return a public URL within 90 seconds.' }
        }
    }
    $state.publicUrl = $publicUrl
    $state.status = 'starting'; Save-State
    foreach ($name in @('frontend','backend')) { Stop-OwnedProcess $name }
    $state.release = $release; Save-State
    $origins = 'http://localhost:5173,http://127.0.0.1:5173'
    if ($publicUrl) { $origins += ",$publicUrl" }
    Start-OwnedProcess 'backend' $java @('-jar', ('"' + (Join-Path $release 'app.jar') + '"'), '--server.address=127.0.0.1', '--spring.jpa.hibernate.ddl-auto=validate', "--app.web.allowed-origins=$origins") (Join-Path $root 'backend')
    Wait-Health 'http://127.0.0.1:8080/api/health'
    $env:HNEXAM_FRONTEND_DIST = Join-Path $release 'frontend'
    $env:HNEXAM_PUBLIC_URL = $publicUrl
    $env:HNEXAM_FRONTEND_PORT = '5173'
    Start-OwnedProcess 'frontend' $Node @(('"' + $server + '"')) $root
    Wait-Health 'http://127.0.0.1:5173/api/health' 30
    $page = Invoke-WebRequest 'http://127.0.0.1:5173/' -UseBasicParsing -TimeoutSec 10
    if ($page.StatusCode -ne 200 -or $page.Content -notmatch '/assets/') { throw 'Built frontend check failed.' }
    if ($publicUrl) {
        Wait-Health "$publicUrl/api/health" 90
        $page = Invoke-WebRequest "$publicUrl/" -UseBasicParsing -TimeoutSec 15
        if ($page.StatusCode -ne 200 -or $page.Content -notmatch '/assets/') { throw 'Public frontend check failed.' }
    }
    $state.status = if ($publicUrl) { 'public-ready' } else { 'local-ready' }
    Save-State
    [IO.File]::WriteAllText($urlPath, $publicUrl)
    Write-Host 'Local: http://127.0.0.1:5173' -ForegroundColor Green
    if ($publicUrl) { Write-Host "Public: $publicUrl" -ForegroundColor Green }
    Write-Host "State: $statePath"
} catch {
    $failure = $_
    if ($newTunnel) {
        try { Stop-OwnedProcess 'tunnel' } catch { Write-Warning 'Could not stop the newly created tunnel. Inspect the saved process state.' }
        $state.publicUrl = ''
        [IO.File]::WriteAllText($urlPath, '')
    }
    if ($lock) { $state.status = 'failed'; Save-State }
    throw $failure
} finally {
    $env:JAVA_HOME = $oldJavaHome
    $env:Path = $oldPath
    $env:HNEXAM_FRONTEND_DIST = $oldDist
    $env:HNEXAM_PUBLIC_URL = $oldPublicUrl
    $env:HNEXAM_FRONTEND_PORT = $oldPort
    if ($lock) { $lock.Dispose() }
}
