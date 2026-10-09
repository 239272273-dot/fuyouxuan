$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $ProjectRoot
$keeperPidFile = Join-Path (Join-Path $ProjectRoot "runtime") "wsl-keeper.pid"

function Stop-FuYouXuanWslKeeper {
    if (-not (Test-Path -LiteralPath $keeperPidFile)) {
        return
    }

    try {
        $keeperProcessId = [int](Get-Content -Raw -LiteralPath $keeperPidFile).Trim()
        $keeperProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $keeperProcessId" -ErrorAction SilentlyContinue
        if ($null -ne $keeperProcess -and
            $keeperProcess.Name -eq "wsl.exe" -and
            $keeperProcess.CommandLine -like "*sleep 2147483647*") {
            Stop-Process -Id $keeperProcessId -Force
        }
    } catch {
        Write-Warning "Could not stop the WSL2 keepalive process: $($_.Exception.Message)"
    } finally {
        Remove-Item -LiteralPath $keeperPidFile -Force -ErrorAction SilentlyContinue
    }
}

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

Stop-FuYouXuanWslKeeper

Write-Host "Containers stopped. Named volumes and demo data were preserved."
