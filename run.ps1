# The Journal - build and run helper (Windows / PowerShell)
# Compiles the Java backend and starts the server. The server prints the URL it
# started on (prefers 8080, auto-falls back to a free port if that is reserved).

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$out = Join-Path $root "backend\out"

Write-Host "Compiling Java backend..." -ForegroundColor Cyan
New-Item -ItemType Directory -Force -Path $out | Out-Null

$sources = Get-ChildItem -Path (Join-Path $root "backend") -Filter *.java | ForEach-Object { $_.FullName }
javac -d $out $sources
if ($LASTEXITCODE -ne 0) {
    Write-Host "Compilation failed." -ForegroundColor Red
    exit 1
}

Write-Host "Starting server..." -ForegroundColor Green
Set-Location $root
java -cp $out JournalServer
