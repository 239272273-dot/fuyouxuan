$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $ProjectRoot
$reportedByChild = $false

if (-not (Test-Path ".env")) {
    $rootPassword = ([guid]::NewGuid().ToString("N") + [guid]::NewGuid().ToString("N"))
    $appPassword = ([guid]::NewGuid().ToString("N") + [guid]::NewGuid().ToString("N"))
    # MySQL replication's report-password field is limited; keep this <= 32 bytes.
    $canalPassword = [guid]::NewGuid().ToString("N").Substring(0, 24)
    @(
        "MYSQL_ROOT_PASSWORD=$rootPassword"
        "MYSQL_APP_PASSWORD=$appPassword"
        "CANAL_DB_PASSWORD=$canalPassword"
        "HTTP_PORT=8080"
    ) | Set-Content -Encoding ascii ".env"
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
    wsl.exe --cd $ProjectRoot bash -lc "bash scripts/docker-up.sh"
    if ($LASTEXITCODE -ne 0) {
        throw "Docker startup inside WSL failed."
    }
    $reportedByChild = $true
} else {
    throw "Docker was not found. Install Docker Desktop or Docker Engine inside WSL2 first."
}

if (-not $reportedByChild) {
    Write-Host ""
    Write-Host "FuYouXuan is ready: http://localhost:$httpPort"
}
