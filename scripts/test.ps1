$ErrorActionPreference = 'Stop'
$pom = Join-Path $PSScriptRoot '../pom.xml'
& mvn -B -f $pom @args verify
if ($LASTEXITCODE -ne 0) { throw "Maven verification failed with exit code $LASTEXITCODE" }
