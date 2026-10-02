param(
    [string]$JavaHome = $env:JAVA_HOME,
    [int]$Port = 3210,
    [string]$DataDir = (Join-Path $env:USERPROFILE '.dongran-work'),
    [switch]$Build,
    [switch]$BuildSandbox,
    [switch]$Restart
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$liveDirectory = Join-Path $projectRoot '.runtime\live'
$sourceJar = Join-Path $projectRoot 'backend\target\dongran-backend-0.1.0-SNAPSHOT.jar'
$liveJar = Join-Path $liveDirectory 'dongran-backend.jar'
$sourceHelper = Join-Path $projectRoot 'sandbox\target\release\dongran-sandbox.exe'
$liveHelper = Join-Path $liveDirectory 'dongran-sandbox.exe'
$pidFile = Join-Path $liveDirectory 'backend.pid'
New-Item -ItemType Directory -Force -Path $liveDirectory | Out-Null
if ($BuildSandbox) { & (Join-Path $PSScriptRoot 'build-sandbox.ps1') }
if ($Build) {
    Push-Location $projectRoot
    try {
        & (Join-Path $projectRoot 'mvnw.cmd') -q package
        if ($LASTEXITCODE -ne 0) { throw 'Backend build failed.' }
    } finally { Pop-Location }
}
if (-not (Test-Path -LiteralPath $sourceJar)) { throw 'Build the backend first: .\mvnw.cmd package' }
if ($JavaHome) { $javaExecutable = Join-Path $JavaHome 'bin\java.exe' }
else { $javaExecutable = (Get-Command java -ErrorAction Stop).Source }
if (-not (Test-Path -LiteralPath $javaExecutable)) { throw 'Java 21 not found. Set JAVA_HOME or pass -JavaHome.' }
if (Test-Path -LiteralPath $pidFile) {
    $savedPid = [int](Get-Content -LiteralPath $pidFile -Raw).Trim()
    $existing = Get-CimInstance Win32_Process -Filter "ProcessId = $savedPid"
    if ($existing -and $existing.CommandLine -like "*$liveJar*") {
        if (-not $Restart) {
            Write-Output "Dongran is already running. PID: $savedPid"
            Write-Output "Open http://127.0.0.1:$Port"
            return
        }
        Stop-Process -Id $savedPid
        Wait-Process -Id $savedPid -ErrorAction SilentlyContinue
    }
}
if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
    throw "Port $Port is occupied. Choose another -Port; no unrelated process was stopped."
}
# Run a copy so the application does not lock Maven's build output.
Copy-Item -LiteralPath $sourceJar -Destination $liveJar -Force
# Use an explicit helper path. Missing isolation must not silently become unrestricted execution.
$configuredHelper = $sourceHelper
if (Test-Path -LiteralPath $sourceHelper -PathType Leaf) {
    Copy-Item -LiteralPath $sourceHelper -Destination $liveHelper -Force
    $configuredHelper = $liveHelper
    Write-Output "Sandbox helper: $configuredHelper (capabilities are checked by the backend)"
} else {
    Write-Output 'Sandbox helper is not built. Native isolation is unavailable; run scripts/build-sandbox.ps1 to build it.'
}
$resolvedData = [IO.Path]::GetFullPath($DataDir)
$arguments = @('-jar', "`"$liveJar`"", "--server.port=$Port", "`"--dongran.data-dir=$resolvedData`"", "`"--dongran.sandbox-helper=$configuredHelper`"")
$process = Start-Process -FilePath $javaExecutable -ArgumentList $arguments `
    -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $liveDirectory 'backend.out.log') `
    -RedirectStandardError (Join-Path $liveDirectory 'backend.err.log')
Set-Content -LiteralPath $pidFile -Value $process.Id
$deadline = (Get-Date).AddSeconds(30)
while ((Get-Date) -lt $deadline) {
    $process.Refresh()
    if ($process.HasExited) { throw 'Backend exited. See .runtime/live/backend.err.log and backend.out.log.' }
    try {
        $health = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/health" -TimeoutSec 2
        if ($health.status -eq 'ok') {
            Write-Output "Dongran frontend and backend are ready. PID: $($process.Id)"
            Write-Output "Open http://127.0.0.1:$Port"
            return
        }
    } catch { Start-Sleep -Milliseconds 250 }
}
throw 'Startup timed out. See .runtime/live/backend.out.log.'
