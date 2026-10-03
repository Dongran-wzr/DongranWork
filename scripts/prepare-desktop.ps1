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
$runtimeRoot = Join-Path $resourceRoot 'runtime'
if (-not $env:JAVA_HOME) { throw 'JAVA_HOME must point to a Java 21 JDK.' }
$jlink = Join-Path $env:JAVA_HOME 'bin/jlink.exe'
if (Test-Path -LiteralPath $runtimeRoot) {
    $resolvedRuntime = [IO.Path]::GetFullPath($runtimeRoot)
    $expectedRuntime = [IO.Path]::GetFullPath((Join-Path $projectRoot 'desktop/src-tauri/resources/runtime'))
    if ($resolvedRuntime -ne $expectedRuntime) { throw 'Unexpected runtime directory.' }
    Remove-Item -LiteralPath $resolvedRuntime -Recurse -Force
}
# Keep all JDK modules for this beta: JDBC, JNA, TLS and reflective libraries need more
# than jdeps can infer. Reduce modules only after packaged integration validation.
& $jlink --add-modules ALL-MODULE-PATH --strip-debug --no-header-files --no-man-pages --output $runtimeRoot
if ($LASTEXITCODE -ne 0) { throw 'Bundled Java runtime creation failed.' }
Write-Output 'Windows resources include Java runtime and sandbox helper.'
