$ErrorActionPreference = "Stop"
$repositoryRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$originalLocation = Get-Location
$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ("fuyouxuan-script-test-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path (Join-Path $fixtureRoot "scripts") -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $repositoryRoot "scripts\docker-up.ps1") -Destination (Join-Path $fixtureRoot "scripts")
Copy-Item -LiteralPath (Join-Path $repositoryRoot "scripts\docker-down.ps1") -Destination (Join-Path $fixtureRoot "scripts")
[IO.File]::WriteAllText((Join-Path $fixtureRoot ".env"), "HTTP_PORT=8080`n")
$global:FuYouXuanTestCalls = New-Object 'System.Collections.Generic.List[string]'

function docker {
    $commandText = $args -join " "
    $global:FuYouXuanTestCalls.Add("docker $commandText")
    $global:LASTEXITCODE = 0
    if ($args[0] -eq "info" -and $global:FuYouXuanTestScenario -in @("unavailable", "wsl-up", "wsl-down", "wsl-failed")) {
        $global:LASTEXITCODE = 1
    } elseif ($args[0] -eq "compose" -and $args[1] -eq "up" -and $global:FuYouXuanTestScenario -eq "up-failed") {
        $global:LASTEXITCODE = 23
    } elseif ($args[0] -eq "compose" -and $args[1] -eq "down" -and $global:FuYouXuanTestScenario -eq "down-failed") {
        $global:LASTEXITCODE = 23
    }
}

function wsl.exe {
    $global:FuYouXuanTestCalls.Add("wsl " + ($args -join " "))
    $global:LASTEXITCODE = 0
    if ($global:FuYouXuanTestScenario -eq "unavailable" -or
        ($global:FuYouXuanTestScenario -eq "wsl-failed" -and $args[-1] -eq "bash scripts/docker-up.sh")) {
        $global:LASTEXITCODE = 1
    }
}

function Invoke-WebRequest {
    $global:FuYouXuanTestCalls.Add("endpoint")
    return [pscustomobject]@{ StatusCode = 200 }
}

function Start-Process {
    $global:FuYouXuanTestCalls.Add("keeper-start")
    return [pscustomobject]@{ Id = 99999 }
}

function Get-CimInstance {
    return [pscustomobject]@{ Name = "wsl.exe"; CommandLine = "wsl.exe --exec sleep 2147483647"; ProcessId = 99999 }
}

function Stop-Process {
    $global:FuYouXuanTestCalls.Add("keeper-stop")
}

function Start-Sleep {}

function Test-Scenario {
    param([string]$Scenario, [string]$ScriptName, [string]$ExpectedError)
    $global:FuYouXuanTestScenario = $Scenario
    $global:FuYouXuanTestCalls.Clear()
    $actualError = ""
    try {
        & (Join-Path $fixtureRoot "scripts\$ScriptName") | Out-Null
    } catch {
        $actualError = $_.Exception.Message
    }
    if ($actualError -ne $ExpectedError) {
        throw "${Scenario}: expected '$ExpectedError', got '$actualError'."
    }
    if ($ExpectedError -and ($global:FuYouXuanTestCalls -contains "endpoint" -or $global:FuYouXuanTestCalls -contains "keeper-stop")) {
        throw "${Scenario}: continued after a failed command."
    }
    if ($Scenario -eq "wsl-up" -and -not ($global:FuYouXuanTestCalls | Where-Object { $_ -like "wsl *bash scripts/docker-up.sh" })) {
        throw "Windows Docker failure did not fall back to WSL."
    }
    if ($Scenario -eq "wsl-down" -and -not ($global:FuYouXuanTestCalls | Where-Object { $_ -like "wsl *bash scripts/docker-down.sh" })) {
        throw "Shutdown did not use WSL."
    }
    Write-Output "PASS: $Scenario"
}

try {
    Test-Scenario "unavailable" "docker-up.ps1" "No Docker engine is reachable. Start Docker Desktop or Docker Engine inside WSL2, then retry."
    Test-Scenario "up-failed" "docker-up.ps1" "Docker Compose startup failed. Check the errors above."
    Test-Scenario "down-failed" "docker-down.ps1" "Docker Compose shutdown failed. Check the errors above."
    Test-Scenario "wsl-failed" "docker-up.ps1" "Docker startup inside WSL failed."
    Test-Scenario "wsl-up" "docker-up.ps1" $null
    Test-Scenario "wsl-down" "docker-down.ps1" $null
    Test-Scenario "windows-up" "docker-up.ps1" $null
} finally {
    Set-Location $originalLocation
    $resolvedFixture = [IO.Path]::GetFullPath($fixtureRoot)
    if ((Split-Path -Parent $resolvedFixture) -eq ([IO.Path]::GetTempPath()).TrimEnd('\') -and
        (Split-Path -Leaf $resolvedFixture) -like "fuyouxuan-script-test-*") {
        Remove-Item -LiteralPath $resolvedFixture -Recurse -Force
    }
}
