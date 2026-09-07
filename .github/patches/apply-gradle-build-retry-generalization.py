from pathlib import Path


path = Path("build.ps1")
text = path.read_text(encoding="utf-8")

if text.count("Get-BridgeBuildAttempts") < 2:
    raise SystemExit("expected post-BUG bridge retry helpers")
text = text.replace("Get-BridgeBuildAttempts", "Get-GradleBuildAttempts")
text = text.replace("Test-TransientBridgeBuildFailure", "Test-TransientGradleBuildFailure")
text = text.replace(
    "$configured = $env:MINESPORT_BRIDGE_BUILD_ATTEMPTS\n    if ([string]::IsNullOrWhiteSpace($configured)) { return 3 }",
    "$configured = $env:MINESPORT_GRADLE_BUILD_ATTEMPTS\n    if ([string]::IsNullOrWhiteSpace($configured)) {\n        # Keep the bridge-specific override as a compatibility alias.\n        $configured = $env:MINESPORT_BRIDGE_BUILD_ATTEMPTS\n    }\n    if ([string]::IsNullOrWhiteSpace($configured)) { return 3 }",
    1,
)
text = text.replace(
    'throw "MINESPORT_BRIDGE_BUILD_ATTEMPTS must be an integer from 1 to 5, got \'$configured\'."',
    'throw "MINESPORT_GRADLE_BUILD_ATTEMPTS must be an integer from 1 to 5, got \'$configured\'."',
    1,
)

start = text.find("function Invoke-BridgeGradleBuild($bridge) {")
end = text.find("function Build-Bridge($bridge) {", start)
if start < 0 or end < 0:
    raise SystemExit("could not locate bridge retry implementation")

replacement = r'''function Invoke-GradleBuildWithRetry(
    [string]$Label,
    [string]$Slug,
    [bool]$FreshBuild
) {
    $attempts = Get-GradleBuildAttempts
    $attemptLog = Join-Path ([IO.Path]::GetTempPath()) (
        "minesport-$Slug-gradle-$([Guid]::NewGuid().ToString('N')).log"
    )
    $lastExitCode = 0

    try {
        for ($attempt = 1; $attempt -le $attempts; $attempt++) {
            Remove-Item -LiteralPath $attemptLog -Force -ErrorAction SilentlyContinue
            if ($FreshBuild) {
                & .\gradlew.bat --no-daemon --stacktrace clean build --rerun-tasks --no-build-cache 2>&1 |
                    Tee-Object -FilePath $attemptLog
            } else {
                & .\gradlew.bat --no-daemon --stacktrace build 2>&1 |
                    Tee-Object -FilePath $attemptLog
            }
            $lastExitCode = $LASTEXITCODE
            if ($lastExitCode -eq 0) {
                if ($attempt -gt 1) {
                    Write-Host "     $Label recovered after transient Gradle/download failure (attempt $attempt/$attempts)" -ForegroundColor Green
                }
                return
            }

            $transient = Test-TransientGradleBuildFailure $attemptLog
            if (-not $transient -or $attempt -ge $attempts) {
                if ($transient) {
                    throw "$Label build failed after $attempt attempts (last exit code $lastExitCode)."
                }
                throw "$Label build failed with exit code $lastExitCode (non-transient failure; not retrying)."
            }

            $delaySeconds = [Math]::Min(20, 5 * $attempt)
            Write-Warning "$Label hit a transient network/download failure (attempt $attempt/$attempts). Preserving caches and retrying in $delaySeconds seconds..."
            Start-Sleep -Seconds $delaySeconds
        }
    } finally {
        Remove-Item -LiteralPath $attemptLog -Force -ErrorAction SilentlyContinue
    }
}

function Invoke-BridgeGradleBuild($bridge) {
    Invoke-GradleBuildWithRetry "$($bridge.Name) Export Worker" $bridge.Slug $Fresh
}

'''
text = text[:start] + replacement + text[end:]

engine_old = r'''            if ($Fresh) {
            & .\gradlew.bat --no-daemon --stacktrace clean build --rerun-tasks --no-build-cache
        } else {
            & .\gradlew.bat --no-daemon --stacktrace build
        }
            if ($LASTEXITCODE -ne 0) { throw 'Java engine build failed.' }
'''
if text.count(engine_old) != 1:
    raise SystemExit(f"engine Gradle anchor: expected one, found {text.count(engine_old)}")
text = text.replace(
    engine_old,
    '''            Invoke-GradleBuildWithRetry 'Java engine' 'engine' $Fresh\n''',
    1,
)

path.write_text(text, encoding="utf-8")
