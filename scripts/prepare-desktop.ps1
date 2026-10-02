param(
    [switch]$SkipBackendBuild
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
& (Join-Path $PSScriptRoot 'build-sandbox.ps1')
if (-not $SkipBackendBuild) {
    Push-Location $projectRoot
    try {
        & (Join-Path $projectRoot 'mvnw.cmd') -q package
        if ($LASTEXITCODE -ne 0) { throw 'Backend build failed.' }
    } finally { Pop-Location }
}
$helperName = if ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) { 'dongran-sandbox.exe' } else { 'dongran-sandbox' }
$resourceRoot = Join-Path $projectRoot 'desktop/src-tauri/resources'
$helperRoot = Join-Path $resourceRoot 'sandbox'
New-Item -ItemType Directory -Force -Path $helperRoot | Out-Null
Copy-Item -LiteralPath (Join-Path $projectRoot 'backend/target/dongran-backend-0.1.0-SNAPSHOT.jar') -Destination (Join-Path $resourceRoot 'backend.jar') -Force
Copy-Item -LiteralPath (Join-Path $projectRoot "sandbox/target/release/$helperName") -Destination (Join-Path $helperRoot $helperName) -Force
Write-Output "Desktop resources staged in $resourceRoot"
Write-Output 'Build Tauri on this same operating system and architecture. Java runtime packaging and installer signing still require release setup.'
