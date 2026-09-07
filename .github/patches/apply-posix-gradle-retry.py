from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one anchor, found {count}")
    return text.replace(old, new, 1)


path = Path("build.sh")
text = path.read_text(encoding="utf-8")

old = r'''run_gradle_build() {
  if $FRESH; then
    ./gradlew --no-daemon clean build --rerun-tasks --no-build-cache
  else
    ./gradlew --no-daemon build
  fi
}
'''
new = r'''run_gradle_once() {
  if $FRESH; then
    ./gradlew --no-daemon clean build --rerun-tasks --no-build-cache
  else
    ./gradlew --no-daemon build
  fi
}

transient_gradle_failure() {
  local log="$1"
  grep -Eiq \
    'Connection reset|Connection timed out|Connect timed out|Read timed out|UnknownHostException|Temporary failure in name resolution|Could not (GET|HEAD) .*https?://|Remote host terminated.*handshake|Premature EOF|HTTP (response )?(code )?(429|5[0-9]{2})' \
    "$log"
}

run_gradle_build() {
  local label="${1:-Gradle}"
  local attempts="${MINESPORT_GRADLE_BUILD_ATTEMPTS:-${MINESPORT_BRIDGE_BUILD_ATTEMPTS:-3}}"
  [[ "$attempts" =~ ^[1-5]$ ]] || {
    echo "ERROR: MINESPORT_GRADLE_BUILD_ATTEMPTS must be an integer from 1 to 5, got '$attempts'." >&2
    return 2
  }

  local log status delay
  log="$(mktemp "${TMPDIR:-/tmp}/minesport-gradle.XXXXXX")"
  for ((attempt = 1; attempt <= attempts; attempt++)); do
    : >"$log"
    if run_gradle_once 2>&1 | tee "$log"; then
      if (( attempt > 1 )); then
        printf '     %s recovered after transient Gradle/download failure (attempt %d/%d)\n' \
          "$label" "$attempt" "$attempts"
      fi
      rm -f "$log"
      return 0
    else
      status="${PIPESTATUS[0]}"
    fi

    if ! transient_gradle_failure "$log" || (( attempt >= attempts )); then
      if transient_gradle_failure "$log"; then
        echo "ERROR: $label build failed after $attempt attempts (last exit code $status)." >&2
      else
        echo "ERROR: $label build failed with exit code $status (non-transient failure; not retrying)." >&2
      fi
      rm -f "$log"
      return "$status"
    fi

    delay=$((attempt * 5))
    (( delay > 20 )) && delay=20
    printf 'WARNING: %s hit a transient network/download failure (attempt %d/%d). Preserving caches and retrying in %d seconds...\n' \
      "$label" "$attempt" "$attempts" "$delay" >&2
    sleep "$delay"
  done

  rm -f "$log"
  return 1
}
'''
text = replace_once(text, old, new, "POSIX Gradle retry helper")
text = replace_once(
    text,
    '''    run_gradle_build\n''',
    '''    run_gradle_build "$name Export Worker"\n''',
    "loader Gradle retry label",
)
text = replace_once(
    text,
    '''      run_gradle_build\n''',
    '''      run_gradle_build "Java engine"\n''',
    "engine Gradle retry label",
)
path.write_text(text, encoding="utf-8")
