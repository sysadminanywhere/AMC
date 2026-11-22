$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$classes = Join-Path $root 'build/classes'
$javac = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/javac' } else { 'javac' }
$moduleSources = 'amc-core', 'amc-training', 'amc-benchmark' | ForEach-Object {
    Join-Path $root "$_/src/main/java"
}
$sources = Get-ChildItem -Path $moduleSources -Recurse -File -Filter '*.java' | ForEach-Object FullName
New-Item -ItemType Directory -Force -Path $classes | Out-Null
& $javac -encoding UTF-8 -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }
