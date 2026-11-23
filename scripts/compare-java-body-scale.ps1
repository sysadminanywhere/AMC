param(
    [string]$Corpus = 'build/java-body-scale-v1',
    [string]$Destination = 'build/java-body-scale-run',
    [ValidateRange(1000, 100000)][int]$SnapshotUpdates = 4000,
    [ValidateRange(1, 8)][int]$Snapshots = 2,
    [long]$Seed = 42
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
if ($SnapshotUpdates % 1000 -ne 0) { throw 'SnapshotUpdates must be divisible by 1000' }
if (Test-Path -LiteralPath $Destination) { throw "Choose a new destination: $Destination" }
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
$classes = Join-Path $PSScriptRoot '../build/classes'
$output = (New-Item -ItemType Directory -Path $Destination).FullName

$selection = foreach ($size in 250, 1000) {
    $root = Join-Path $Corpus "$size"
    $tasks = @(Get-ChildItem -LiteralPath (Join-Path $root 'train') -Directory)
    if ($tasks.Count -ne $size) { throw "Expected $size training tasks, found $($tasks.Count)" }
    $prompts = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($task in $tasks) {
        if (-not $prompts.Add([IO.File]::ReadAllText((Join-Path $task.FullName 'prompt.txt')))) {
            throw "Training prompts collide after case normalization: $($task.Name)"
        }
    }
    $snapshotsDir = Join-Path $output "snapshots-$size"
    $interval = [int]($SnapshotUpdates / $size)
    & $java '-Xmx1g' -cp $classes dev.amc.training.TrainPrograms --body $root (Join-Path $output "loss-$size.bin") `
        ($interval * $Snapshots) $Seed $snapshotsDir $interval |
        Tee-Object -FilePath (Join-Path $output "training-$size.txt") | Out-Host

    $best = $null
    for ($step = 1; $step -le $Snapshots; $step++) {
        $updates = $step * $SnapshotUpdates
        $weights = Join-Path $snapshotsDir "updates-$updates.bin"
        $lines = @(& $java -cp $classes dev.amc.benchmark.JavaProgramBenchmark --body --stop-on-repeat $root $weights validation)
        $lines | Tee-Object -FilePath (Join-Path $output "validation-$size-$updates.txt") | Out-Host
        if ($lines[-1] -notmatch '^mode=.* split=validation tasks=\d+ passed=(?<passed>\d+) ') {
            throw "Missing validation summary for $size examples at $updates updates"
        }
        $passed = [int]$Matches.passed
        if ($null -eq $best -or $passed -gt $best.ValidationPassed) {
            $best = [pscustomobject]@{ Size = $size; Updates = $updates; ValidationPassed = $passed; Weights = $weights }
        }
    }
    $best
}

$selection | Export-Csv -LiteralPath (Join-Path $output 'selection.csv') -NoTypeInformation
foreach ($selected in $selection) {
    $root = Join-Path $Corpus "$($selected.Size)"
    & $java -cp $classes dev.amc.benchmark.JavaProgramBenchmark --body --stop-on-repeat $root $selected.Weights test |
        Tee-Object -FilePath (Join-Path $output "test-$($selected.Size).txt")
}
