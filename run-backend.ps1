param(
    [switch]$SkipBuild
)

# Builds (unless -SkipBuild) and starts the TypeRush backend on typerush-server port 8081.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

& taskkill /F /IM java.exe /FI "WINDOWTITLE eq typerush*" 2>$null | Out-Null
Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -like '*typerush-server*' } |
    ForEach-Object { & taskkill /F /PID $_.ProcessId | Out-Null }

Start-Sleep -Seconds 1

# Pin the port so the app and the Vite proxy can never disagree.
$env:SERVER_PORT = '8081'

if (-not $SkipBuild) {
    Push-Location $root
    mvn -q package -DskipTests
    if ($LASTEXITCODE -ne 0) { Pop-Location; throw 'backend build failed' }
    Pop-Location
}

Start-Process -FilePath 'java' `
    -ArgumentList '-jar', "$root\target\typerush-server-1.0.0.jar" `
    -WorkingDirectory $root `
    -RedirectStandardOutput "$root\server.log" `
    -RedirectStandardError "$root\server.err.log" `
    -WindowStyle Hidden

$deadline = (Get-Date).AddSeconds(60)
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 2
    try {
        $health = Invoke-RestMethod 'http://localhost:8081/api/health' -TimeoutSec 3
        Write-Output "backend up: status=$($health.status) texts=$($health.textCount)"
        exit 0
    } catch {
        # keep polling until the port answers
    }
}

Write-Output 'backend did not become healthy in 60s; see backend\server.log'
exit 1