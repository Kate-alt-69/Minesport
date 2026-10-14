$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$tokens = $null
$parseErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile(
    (Join-Path $repoRoot 'build.ps1'), [ref]$tokens, [ref]$parseErrors
)
if ($parseErrors.Count -gt 0) { throw ($parseErrors | Out-String) }

# Load the actual build functions without running Rust, Gradle or packaging.
foreach ($name in @('Get-BridgeBuildAttempts', 'Test-TransientBridgeBuildFailure', 'Invoke-BridgeGradleBuild', 'Build-Bridge')) {
    $definition = $ast.Find({
        param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name
    }, $true)
    if (-not $definition) { throw "Missing build function: $name" }
    . ([scriptblock]::Create($definition.Extent.Text))
}

function Assert([bool]$condition, [string]$message) {
    if (-not $condition) { throw $message }
}
function Start-Sleep { param([int]$Seconds) }
function Get-TreeFingerprint { param([string]$Path) return 'fixture-input' }
function Test-NeedsRebuild { return $true }
function Save-BuildState { $script:saves++ }

$fixture = Join-Path ([IO.Path]::GetTempPath()) ('Minesport build retry ' + [Guid]::NewGuid().ToString('N'))
$oldAttempts = $env:MINESPORT_BRIDGE_BUILD_ATTEMPTS
$env:MINESPORT_BRIDGE_BUILD_ATTEMPTS = '3'
$Fresh = $false
try {
    foreach ($scenario in @('recover', 'timeout', 'compile', 'missing-jar', 'success')) {
        $Root = Join-Path $fixture $scenario
        $project = Join-Path $Root 'minesport-bridge-fabric'
        New-Item -ItemType Directory -Force -Path $project | Out-Null
        $counter = @'
@echo off
set /a ATTEMPTS=0
if exist attempts.txt set /p ATTEMPTS=<attempts.txt
set /a ATTEMPTS+=1
>attempts.txt echo %ATTEMPTS%
'@
        $behavior = switch ($scenario) {
            'recover' { @'
if %ATTEMPTS%==1 (
    echo Exception in thread main java.net.ConnectException: Connection timed out: getsockopt 1>&2
    exit /b 1
)
exit /b 0
'@ }
            'timeout' { @'
echo Downloading https://services.gradle.org/distributions/gradle-8.14-bin.zip
echo Exception in thread main java.net.ConnectException: Connection timed out: getsockopt 1>&2
exit /b 1
'@ }
            'compile' { "echo error: cannot find symbol 1>&2`r`nexit /b 1" }
            'missing-jar' { 'exit /b 0' }
            'success' { "mkdir build\libs`r`n>build\libs\fixture.jar echo fixture`r`nexit /b 0" }
        }
        [IO.File]::WriteAllText((Join-Path $project 'gradlew.bat'), $counter + "`r`n" + $behavior + "`r`n")
        $bridge = @{ Name = 'Fabric'; Slug = 'fabric'; Project = 'minesport-bridge-fabric' }
        $destination = Join-Path $Root 'staged.jar'
        $bundledBridges = @{ fabric = $destination }
        $BuildState = @{}
        $script:saves = 0
        $failure = $null
        Push-Location $project
        try {
            try {
                if ($scenario -eq 'recover') {
                    Invoke-BridgeGradleBuild $bridge | Out-Host
                } else {
                    Build-Bridge $bridge | Out-Host
                }
            } catch {
                $failure = $_.Exception.Message
            }
        } finally {
            Pop-Location
        }
        $attemptCount = [int](Get-Content -LiteralPath (Join-Path $project 'attempts.txt'))
        $logs = @(Get-ChildItem -LiteralPath (Join-Path $Root 'dist\build-logs') -Filter '*.log' -File)
        switch ($scenario) {
            'recover' {
                Assert ($null -eq $failure) "Recovery failed: $failure"
                Assert ($attemptCount -eq 2) 'Timeout must retry once before recovery.'
                Assert ($logs.Count -eq 0) 'Recovered builds should clean their temporary log.'
            }
            'timeout' {
                Assert ($failure -match 'failed after 3 attempts.*exit code 1') "Wrong timeout failure: $failure"
                Assert ($attemptCount -eq 3) 'Persistent timeout must use the bounded retry count.'
                Assert ($logs.Count -eq 1) 'Final timeout log must be retained.'
                Assert ((Get-Content $logs[0].FullName -Raw) -match 'getsockopt') 'Native stderr must reach the retry log.'
            }
            'compile' {
                Assert ($failure -match 'non-transient failure; not retrying') "Wrong compile failure: $failure"
                Assert ($attemptCount -eq 1) 'Compiler errors must fail on the first attempt.'
                Assert ($logs.Count -eq 1) 'Compiler failure log must be retained.'
            }
            'missing-jar' {
                Assert ($failure -match 'reported success but did not produce') "Wrong missing artifact failure: $failure"
                Assert ($attemptCount -eq 1) 'Missing output is not a transient download failure.'
            }
            'success' {
                Assert ($null -eq $failure) "Successful build failed: $failure"
                Assert (Test-Path -LiteralPath $destination) 'Successful artifact must be staged.'
                Assert ($script:saves -eq 1) 'Successful artifact must record its fingerprint.'
            }
        }
        if ($scenario -in @('timeout', 'compile', 'missing-jar')) {
            Assert (-not (Test-Path -LiteralPath $destination)) 'Failed build must not stage an artifact.'
            Assert ($script:saves -eq 0) 'Failed build must not record a successful fingerprint.'
        }
        Write-Host "PASS: $scenario"
    }
} finally {
    if ($null -eq $oldAttempts) {
        Remove-Item Env:MINESPORT_BRIDGE_BUILD_ATTEMPTS -ErrorAction SilentlyContinue
    } else {
        $env:MINESPORT_BRIDGE_BUILD_ATTEMPTS = $oldAttempts
    }
    Remove-Item -LiteralPath $fixture -Recurse -Force -ErrorAction SilentlyContinue
}
