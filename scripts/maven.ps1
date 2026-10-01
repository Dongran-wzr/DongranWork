param([Parameter(ValueFromRemainingArguments = $true)][string[]]$MavenArgs)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$version = '3.9.11'
$distribution = Join-Path $root ".tools/apache-maven-$version"
$executable = Join-Path $distribution 'bin/mvn.cmd'
if (-not (Test-Path -LiteralPath $executable)) {
    $toolsDirectory = Join-Path $root '.tools'
    New-Item -ItemType Directory -Force -Path $toolsDirectory | Out-Null
    $archive = Join-Path $toolsDirectory "apache-maven-$version-bin.zip"
    $url = "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$version/apache-maven-$version-bin.zip"
    Invoke-WebRequest -Uri $url -OutFile $archive
    $expected = ((Invoke-WebRequest -Uri "$url.sha512").Content.Trim() -split '\s+')[0]
    $sha = [System.Security.Cryptography.SHA512]::Create()
    $actual = [BitConverter]::ToString($sha.ComputeHash([IO.File]::ReadAllBytes($archive))).Replace('-', '')
    $sha.Dispose()
    if ($actual -ne $expected) { throw 'Maven distribution checksum mismatch' }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [IO.Compression.ZipFile]::ExtractToDirectory($archive, $toolsDirectory)
}
& $executable @MavenArgs
exit $LASTEXITCODE
