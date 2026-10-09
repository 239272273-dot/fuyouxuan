$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $ProjectRoot

if (Get-Command docker -ErrorAction SilentlyContinue) {
    docker compose down
} elseif (Get-Command wsl.exe -ErrorAction SilentlyContinue) {
    wsl.exe --cd $ProjectRoot bash -lc "bash scripts/docker-down.sh"
    if ($LASTEXITCODE -ne 0) {
        throw "Docker shutdown inside WSL failed."
    }
} else {
    throw "Docker was not found."
}

Write-Host "Containers stopped. Named volumes and demo data were preserved."
