$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $ProjectRoot
$reportedByChild = $false
$runtimeDir = Join-Path $ProjectRoot "runtime"
$keeperPidFile = Join-Path $runtimeDir "wsl-keeper.pid"

function Test-FuYouXuanEndpoint {
    param([string]$Port)
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$Port/health" -TimeoutSec 5
        return $response.StatusCode -eq 200
    } catch {
        return $false
    }
}

function Get-FuYouXuanWslKeeper {
    if (-not (Test-Path -LiteralPath $keeperPidFile)) {
        return $null
    }

    try {
        $keeperProcessId = [int](Get-Content -Raw -LiteralPath $keeperPidFile).Trim()
    } catch {
        return $null
    }

    $keeperProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $keeperProcessId" -ErrorAction SilentlyContinue
    if ($null -ne $keeperProcess -and
        $keeperProcess.Name -eq "wsl.exe" -and
        $keeperProcess.CommandLine -like "*sleep 2147483647*") {
        return $keeperProcess
    }

    return $null
}

function Start-FuYouXuanWslKeeper {
    if ($null -ne (Get-FuYouXuanWslKeeper)) {
        return
    }

    New-Item -ItemType Directory -Path $runtimeDir -Force | Out-Null
    if (Test-Path -LiteralPath $keeperPidFile) {
        Remove-Item -LiteralPath $keeperPidFile -Force
    }

    # Keep one hidden WSL client attached. Without it, some WSL2 installations
    # stop the distribution (and every Docker container) as soon as this script exits.
    $keeperProcess = Start-Process -FilePath "wsl.exe" `
        -ArgumentList @("--exec", "sleep", "2147483647") `
        -WindowStyle Hidden `
        -PassThru
    $keeperProcess.Id | Set-Content -Encoding ascii -LiteralPath $keeperPidFile
    Start-Sleep -Seconds 2

    if ($null -eq (Get-FuYouXuanWslKeeper)) {
        throw "Unable to keep WSL2 running in the background."
    }
}

if (-not (Test-Path ".env")) {
    $rootPassword = ([guid]::NewGuid().ToString("N") + [guid]::NewGuid().ToString("N"))
    $appPassword = ([guid]::NewGuid().ToString("N") + [guid]::NewGuid().ToString("N"))
    # MySQL replication's report-password field is limited; keep this <= 32 bytes.
    $canalPassword = [guid]::NewGuid().ToString("N").Substring(0, 24)
    $envLines = @(
        "MYSQL_ROOT_PASSWORD=$rootPassword"
        "MYSQL_APP_PASSWORD=$appPassword"
        "CANAL_DB_PASSWORD=$canalPassword"
        "HTTP_PORT=8080"
    )
    [IO.File]::WriteAllText(
        (Join-Path $ProjectRoot ".env"),
        (($envLines -join "`n") + "`n"),
        [Text.UTF8Encoding]::new($false)
    )
    Write-Host "Created local .env with random passwords."
}

$httpPort = "8080"
$portLine = Get-Content ".env" | Where-Object { $_ -match '^HTTP_PORT=' } | Select-Object -First 1
if ($portLine) {
    $httpPort = ($portLine -split '=', 2)[1].Trim()
}

if (Get-Command docker -ErrorAction SilentlyContinue) {
    docker compose up -d --build --wait --wait-timeout 420
    docker compose ps
} elseif (Get-Command wsl.exe -ErrorAction SilentlyContinue) {
    Start-FuYouXuanWslKeeper
    wsl.exe --cd $ProjectRoot bash -lc "bash scripts/docker-up.sh"
    if ($LASTEXITCODE -ne 0) {
        throw "Docker startup inside WSL failed."
    }
    $reportedByChild = $true

    # Some Windows 10 + WSL2 installations do not keep automatic localhost
    # forwarding active. Add a loopback-only fallback that is refreshed each run.
    if (-not (Test-FuYouXuanEndpoint -Port $httpPort)) {
        $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
        $principal = New-Object Security.Principal.WindowsPrincipal($identity)
        $isAdministrator = $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
        if (-not $isAdministrator) {
            throw "Windows cannot reach WSL through localhost. Re-run PowerShell as Administrator so the startup script can repair the local port forwarding."
        }

        $wslIpOutput = (wsl.exe --cd $ProjectRoot hostname -I).Trim()
        $wslIp = ($wslIpOutput -split '\s+')[0]
        if (-not $wslIp) {
            throw "Unable to determine the WSL2 address."
        }

        netsh interface portproxy delete v4tov4 listenaddress=127.0.0.1 listenport=$httpPort 2>$null | Out-Null
        netsh interface portproxy add v4tov4 listenaddress=127.0.0.1 listenport=$httpPort connectaddress=$wslIp connectport=$httpPort | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "Unable to configure Windows localhost forwarding for WSL2."
        }
        Restart-Service iphlpsvc -Force
        Start-Sleep -Seconds 2
    }
} else {
    throw "Docker was not found. Install Docker Desktop or Docker Engine inside WSL2 first."
}

if (-not (Test-FuYouXuanEndpoint -Port $httpPort)) {
    throw "All containers started, but Windows still cannot reach http://localhost:$httpPort."
}

if (-not $reportedByChild) {
    Write-Host ""
    Write-Host "FuYouXuan is ready: http://localhost:$httpPort"
} else {
    Write-Host "Windows access is ready: http://localhost:$httpPort"
}
