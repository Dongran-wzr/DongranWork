$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Push-Location $root
try {
  & .\mvnw.cmd -q -pl backend -am -DskipTests package
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
  $env:DONGRAN_BACKEND_JAR = Join-Path $root 'backend/target/dongran-backend-0.1.0-SNAPSHOT.jar'
} finally { Pop-Location }
