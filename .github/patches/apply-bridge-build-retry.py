from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one anchor, found {count}")
    return text.replace(old, new, 1)


path = Path("build.ps1")
text = path.read_text(encoding="utf-8")

helper = r'''
function Get-BridgeBuildAttempts {
    $configured = $env:MINESPORT_BRIDGE_BUILD_ATTEMPTS
    if ([string]::IsNullOrWhiteSpace($configured)) { return 3 }

    $attempts = 0
    if (-not [int]::TryParse($configured, [ref]$attempts) -or $attempts -lt 1 -or $attempts -gt 5) {
        throw "MINESPORT_BRIDGE_BUILD_ATTEMPTS must be an integer from 1 to 5, got '$configured'."
    }
    return $attempts
}

function Test-TransientBridgeBuildFailure([string]$LogPath) {
    if (-not (Test-Path -LiteralPath $LogPath)) { return $false }
    $patterns = @(
        'Connection reset',
        'Connection timed out',
        'Connect timed out',
        'Read timed out',
        'UnknownHostException',
        'Temporary failure in name resolution',
        'Could not GET .*https?://',
        'Could not HEAD .*https?://',
        'Remote host terminated.*handshake',
        'Premature EOF',
        'HTTP (?:response )?(?:code )?(?:429|5\d\d)'
    )
    return $null -ne (Select-String -LiteralPath $LogPath -Pattern $patterns -CaseSensitive:$false -ErrorAction SilentlyContinue | Select-Object -First 1)
}

function Invoke-BridgeGradleBuild($bridge) {
    $attempts = Get-BridgeBuildAttempts
    $attemptLog = Join-Path ([IO.Path]::GetTempPath()) (
        "minesport-$($bridge.Slug)-gradle-$([Guid]::NewGuid().ToString('N')).log"
    )
    $lastExitCode = 0

    try {
        for ($attempt = 1; $attempt -le $attempts; $attempt++) {
            Remove-Item -LiteralPath $attemptLog -Force -ErrorAction SilentlyContinue

            # Keep Gradle/Mavenizer caches between attempts. A retry should resume
            # dependency resolution, not throw away already-downloaded artifacts.
            if ($Fresh) {
                & .\gradlew.bat --no-daemon --stacktrace clean build --rerun-tasks --no-build-cache 2>&1 |
                    Tee-Object -FilePath $attemptLog
            } else {
                & .\gradlew.bat --no-daemon --stacktrace build 2>&1 |
                    Tee-Object -FilePath $attemptLog
            }
            $lastExitCode = $LASTEXITCODE
            if ($lastExitCode -eq 0) {
                if ($attempt -gt 1) {
                    Write-Host "     recovered after transient Gradle/download failure (attempt $attempt/$attempts)" -ForegroundColor Green
                }
                return
            }

            $transient = Test-TransientBridgeBuildFailure $attemptLog
            if (-not $transient -or $attempt -ge $attempts) {
                if ($transient) {
                    throw "$($bridge.Name) Export Worker build failed after $attempt attempts (last exit code $lastExitCode)."
                }
                throw "$($bridge.Name) Export Worker build failed with exit code $lastExitCode (non-transient failure; not retrying)."
            }

            $delaySeconds = [Math]::Min(20, 5 * $attempt)
            Write-Warning "$($bridge.Name) Export Worker hit a transient network/download failure (attempt $attempt/$attempts). Preserving caches and retrying in $delaySeconds seconds..."
            Start-Sleep -Seconds $delaySeconds
        }
    } finally {
        Remove-Item -LiteralPath $attemptLog -Force -ErrorAction SilentlyContinue
    }
}

'''
text = replace_once(
    text,
    "function Build-Bridge($bridge) {\n",
    helper + "function Build-Bridge($bridge) {\n",
    "bridge retry helpers",
)

old = r'''        # No clean: if a rebuild is needed, preserve Gradle's local incremental work.
        if ($Fresh) {
            & .\gradlew.bat --no-daemon --stacktrace clean build --rerun-tasks --no-build-cache
        } else {
            & .\gradlew.bat --no-daemon --stacktrace build
        }
        if ($LASTEXITCODE -ne 0) { throw "$($bridge.Name) Export Worker build failed." }
'''
new = r'''        # No clean: if a rebuild is needed, preserve Gradle's local incremental work.
        # Dependency-resolution failures are retried only when their streamed log
        # matches a known transient network condition (connection reset/timeouts,
        # temporary DNS failure, HTTP 429/5xx, etc.). Deterministic compile errors
        # still fail immediately instead of wasting several full builds.
        Invoke-BridgeGradleBuild $bridge
'''
text = replace_once(text, old, new, "bridge Gradle invocation")

path.write_text(text, encoding="utf-8")
