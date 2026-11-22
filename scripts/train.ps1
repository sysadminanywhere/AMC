$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'build.ps1')
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java' } else { 'java' }
& $java -cp (Join-Path $PSScriptRoot '../build/classes') dev.amc.training.Train @args
if ($LASTEXITCODE -ne 0) { throw 'training failed' }
