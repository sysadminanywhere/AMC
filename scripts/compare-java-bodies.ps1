param(
    [string]$Corpus = 'build/java-exercism-body',
    [string]$Destination = 'build/java-body-comparison',
    [int[]]$Epochs = @(50, 100, 200),
    [long]$Seed = 42
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
if ($Epochs.Count -eq 0 -or ($Epochs | Where-Object { $_ -lt 1 })) { throw 'Epoch counts must be positive' }
if (Test-Path -LiteralPath $Destination) { throw "Choose a new destination: $Destination" }
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
$classes = Join-Path $PSScriptRoot '../build/classes'
$output = (New-Item -ItemType Directory -Path $Destination).FullName

$selected = foreach ($format in 'scaffold', 'body') {
    $best = $null
    foreach ($epoch in ($Epochs | Sort-Object -Unique)) {
        $weights = Join-Path $output "$format-$epoch.bin"
        & $java '-Xmx1g' -cp $classes dev.amc.training.TrainPrograms "--$format" $Corpus $weights $epoch $Seed |
            Tee-Object -FilePath (Join-Path $output "$format-$epoch.training.txt") | Out-Host
        $lines = @(& $java -cp $classes dev.amc.benchmark.JavaProgramBenchmark "--$format" $Corpus $weights validation)
        $lines | Tee-Object -FilePath (Join-Path $output "$format-$epoch.validation.txt") | Out-Host
        if ($lines[-1] -notmatch '^mode=.* split=validation tasks=(?<total>\d+) passed=(?<passed>\d+) ') {
            throw "Missing benchmark summary for $format at $epoch epochs"
        }
        $passed = [int]$Matches.passed
        if ($null -eq $best -or $passed -gt $best.Passed) {
            $best = [pscustomobject]@{ Format = $format; Epochs = $epoch; Passed = $passed; Weights = $weights }
        }
    }
    Write-Host "selected=$($best.Format) epochs=$($best.Epochs) validationPassed=$($best.Passed)"
    $best
}

$selected | Export-Csv -LiteralPath (Join-Path $output 'selection.csv') -NoTypeInformation
foreach ($run in $selected) {
    & $java -cp $classes dev.amc.benchmark.JavaProgramBenchmark "--$($run.Format)" $Corpus $run.Weights test |
        Tee-Object -FilePath (Join-Path $output "$($run.Format).test.txt")
}
