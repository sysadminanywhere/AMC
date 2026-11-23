param(
    [string]$Source = 'build/exercism-java',
    [string]$Destination = 'build/java-exercism',
    [string]$Libraries = 'build/exercism-libs',
    [switch]$ExpandedTraining,
    [switch]$BodyExperiment
)

$ErrorActionPreference = 'Stop'
$revision = '84065eb95937f00e029fc690dbea7dd970439abf'
$actual = git -C $Source rev-parse HEAD
if ($LASTEXITCODE -ne 0 -or $actual -ne $revision) { throw "Expected Exercism revision $revision" }
$changes = git -C $Source status --porcelain
if ($LASTEXITCODE -ne 0 -or $changes) { throw 'The Exercism checkout must be clean' }
if (Test-Path -LiteralPath $Destination) { throw "Choose a new destination. Already exists: $Destination" }

$splitPath = Join-Path $PSScriptRoot '../datasets/exercism-java-split.csv'
$rows = @(Import-Csv -LiteralPath $splitPath)
if ($ExpandedTraining -or $BodyExperiment) {
    $extraPath = Join-Path $PSScriptRoot '../datasets/exercism-java-extra-training.csv'
    $extra = @(Import-Csv -LiteralPath $extraPath)
    if ($extra | Where-Object { $_.split -cne 'train' }) {
        throw 'Expanded exercises must be in the training split'
    }
    $rows += $extra
}
$bodyRoot = Join-Path $PSScriptRoot '../datasets/exercism-java-body'
if ($BodyExperiment) {
    $selection = @(Get-Content -LiteralPath (Join-Path $bodyRoot 'tasks.txt') -Encoding UTF8)
    $rows = @($rows | Where-Object { $_.slug -cin $selection })
    if ($rows.Count -ne $selection.Count) { throw 'Body experiment selection does not match the Exercism manifest' }
}
$families = @{}
$seen = @{}
$tasks = foreach ($row in $rows) {
    if ($row.split -cnotin 'train','validation','test' -or $row.slug -cnotmatch '^[a-z0-9-]+$' -or
        $row.family -cnotmatch '^[a-z0-9-]+$' -or $seen.ContainsKey($row.slug)) {
        throw "Invalid or repeated exercise: $($row.slug)"
    }
    if ($families.ContainsKey($row.family) -and $families[$row.family] -ne $row.split) {
        throw "Family crosses splits: $($row.family)"
    }
    $families[$row.family] = $row.split
    $seen[$row.slug] = $true
    $root = Join-Path $Source "exercises/practice/$($row.slug)"
    $metadata = Get-Content -LiteralPath "$root/.meta/config.json" -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($metadata.files.example.Count -ne 1 -or $metadata.files.test.Count -ne 1 -or
        $metadata.files.solution.Count -ne 1) {
        throw "Expected one solution, starter and test file: $($row.slug)"
    }
    $solution = Join-Path $root $metadata.files.example[0]
    $test = Join-Path $root $metadata.files.test[0]
    $support = @($metadata.files.editor | Where-Object { $_ -like '*.java' } | ForEach-Object {
        Get-Item -LiteralPath (Join-Path $root $_)
    })
    $starter = Get-Content -LiteralPath (Join-Path $root $metadata.files.solution[0]) -Raw -Encoding UTF8
    $instructions = Get-Content -LiteralPath "$root/.docs/instructions.md" -Raw -Encoding UTF8
    $testCode = Get-Content -LiteralPath $test -Raw -Encoding UTF8
    if ($testCode -notmatch 'org\.junit\.jupiter\.' -or $testCode -match '(?m)^\s*package\s') {
        throw "Unsupported test layout: $($row.slug)"
    }
    $reference = $solution
    if ($BodyExperiment) {
        $bodyReference = Join-Path $bodyRoot "$($row.slug).java"
        if (Test-Path -LiteralPath $bodyReference -PathType Leaf) { $reference = $bodyReference }
    }
    [pscustomobject]@{ Row=$row; Solution=$solution; Reference=$reference; Test=$test; Starter=$starter; Instructions=$instructions; Support=$support }
}

$junit = Get-Item -LiteralPath (Join-Path $Libraries 'junit-platform-console-standalone-1.10.0.jar')
$assertj = Get-Item -LiteralPath (Join-Path $Libraries 'assertj-core-3.25.1.jar')
if ((Get-FileHash -Algorithm SHA256 -LiteralPath $junit.FullName).Hash -ne '8192D377BDB53969273CA3805AF0EEF3B864690F7818300C0D39AC7758DA39A0' -or
    (Get-FileHash -Algorithm SHA256 -LiteralPath $assertj.FullName).Hash -ne '620DA4E4DE03108C2F412EC17A7ED44B0112DC65E181BD0732DC96FA3B65356F') {
    throw 'Unexpected JUnit or AssertJ artifact checksum'
}
$lib = New-Item -ItemType Directory -Path (Join-Path $Destination 'lib')
Copy-Item -LiteralPath $junit.FullName -Destination (Join-Path $lib.FullName 'junit.jar')
Copy-Item -LiteralPath $assertj.FullName -Destination (Join-Path $lib.FullName 'assertj.jar')
Copy-Item -LiteralPath (Join-Path $Source 'LICENSE') -Destination $Destination
$rows | Export-Csv -LiteralPath (Join-Path $Destination 'split.csv') -NoTypeInformation -Encoding UTF8

$encoding = New-Object System.Text.UTF8Encoding($false)
foreach ($task in $tasks) {
    $row = $task.Row
    $directory = New-Item -ItemType Directory -Path (Join-Path $Destination "$($row.split)/$($row.slug)")
    $sourceName = Split-Path -Leaf $task.Solution
    $testName = Split-Path -Leaf $task.Test
    Copy-Item -LiteralPath $task.Reference -Destination (Join-Path $directory.FullName 'Solution.java')
    Copy-Item -LiteralPath $task.Test -Destination (Join-Path $directory.FullName 'Verify.java')
    [IO.File]::WriteAllText((Join-Path $directory.FullName 'Starter.java'), $task.Starter, $encoding)
    $prompt = "Write the complete $sourceName in Java. Preserve the public API in the starter file.$([Environment]::NewLine)$($task.Instructions.Trim())$([Environment]::NewLine)$($task.Starter.Trim())"
    if ($task.Support.Count -gt 0) {
        $supportDirectory = New-Item -ItemType Directory -Path (Join-Path $directory.FullName 'support')
        foreach ($file in $task.Support) {
            Copy-Item -LiteralPath $file.FullName -Destination $supportDirectory.FullName
            $source = Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8
            $prompt += "`n`nThe following file is supplied separately: $($file.Name)`n$($source.Trim())"
        }
    }
    [IO.File]::WriteAllText((Join-Path $directory.FullName 'prompt.txt'), $prompt, $encoding)
    [IO.File]::WriteAllLines((Join-Path $directory.FullName 'junit.txt'), [string[]]@($sourceName, $testName), $encoding)
    $origin = "https://github.com/exercism/java/tree/$revision/exercises/practice/$($row.slug)"
    [IO.File]::WriteAllText((Join-Path $directory.FullName 'origin.txt'), "$origin`nMIT`n", $encoding)
}
$rows | Group-Object split | Select-Object Name,Count
