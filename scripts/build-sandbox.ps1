param(
    [switch]$Debug
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$localCargoHome = Join-Path $projectRoot '.tools/rust/cargo'
$localRustupHome = Join-Path $projectRoot '.tools/rust/rustup'
$localCargo = Join-Path $localCargoHome 'bin/cargo.exe'
$cargoExecutable = if (Test-Path -LiteralPath $localCargo) { $localCargo } else { (Get-Command cargo -ErrorAction SilentlyContinue).Source }
if (-not $cargoExecutable) {
    throw 'Building the helper requires Rust on the developer machine. Released applications include the helper; users do not install Rust.'
}
$previousCargoHome = $env:CARGO_HOME
$previousRustupHome = $env:RUSTUP_HOME
$previousPath = $env:PATH
try {
    if ($cargoExecutable -eq $localCargo) {
        $env:CARGO_HOME = $localCargoHome
        $env:RUSTUP_HOME = $localRustupHome
        $env:PATH = (Join-Path $localCargoHome 'bin') + [IO.Path]::PathSeparator + $env:PATH
    }
    $cargoArguments = @('build', '--locked', '--manifest-path', (Join-Path $projectRoot 'sandbox/Cargo.toml'))
    $profile = 'debug'
    if (-not $Debug) {
        $cargoArguments += '--release'
        $profile = 'release'
    }
    & $cargoExecutable @cargoArguments
    if ($LASTEXITCODE -ne 0) { throw 'Sandbox helper build failed.' }
    $helperName = if ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) { 'dongran-sandbox.exe' } else { 'dongran-sandbox' }
    $helper = Join-Path $projectRoot "sandbox/target/$profile/$helperName"
    if (-not (Test-Path -LiteralPath $helper -PathType Leaf)) { throw "Built helper was not found at $helper" }
    Write-Output "Sandbox helper: $helper"
} finally {
    $env:CARGO_HOME = $previousCargoHome
    $env:RUSTUP_HOME = $previousRustupHome
    $env:PATH = $previousPath
}
