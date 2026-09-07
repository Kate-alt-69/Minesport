from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    matches = text.count(old)
    if matches != 1:
        raise SystemExit(f"{label} anchor changed (matches={matches})")
    return text.replace(old, new, 1)


path = Path("desktop/src/ipc.rs")
text = path.read_text(encoding="utf-8")

text = replace_once(
    text,
    "const ENGINE_JAVA_MAJOR: u32 = 22;\npub const ENGINE_PROTOCOL_VERSION: u32 = 1;",
    "const ENGINE_JAVA_MAJOR: u32 = 22;\nconst MAX_ENGINE_STDOUT_LINE_BYTES: usize = 128 * 1024 * 1024;\nconst MAX_ENGINE_STDERR_LINE_BYTES: usize = 4 * 1024 * 1024;\nconst MAX_ENGINE_REQUEST_LINE_BYTES: usize = 8 * 1024 * 1024;\npub const ENGINE_PROTOCOL_VERSION: u32 = 1;",
    "IPC size constants",
)

marker = "fn reader_generation_is_current(generation: &AtomicU64, reader_generation: u64) -> bool {"
if text.count(marker) != 1:
    raise SystemExit("reader generation anchor changed")
helper = r'''struct BoundedLines<R> {
    reader: R,
    max_bytes: usize,
    done: bool,
}

fn bounded_lines<R: BufRead>(reader: R, max_bytes: usize) -> BoundedLines<R> {
    BoundedLines {
        reader,
        max_bytes,
        done: false,
    }
}

impl<R: BufRead> Iterator for BoundedLines<R> {
    type Item = std::io::Result<String>;

    fn next(&mut self) -> Option<Self::Item> {
        if self.done {
            return None;
        }
        let mut bytes = Vec::new();
        loop {
            let available = match self.reader.fill_buf() {
                Ok(available) => available,
                Err(error) => {
                    self.done = true;
                    return Some(Err(error));
                }
            };
            if available.is_empty() {
                self.done = true;
                if bytes.is_empty() {
                    return None;
                }
                break;
            }

            let newline = available.iter().position(|byte| *byte == b'\n');
            let append_len = newline.unwrap_or(available.len());
            if bytes.len().saturating_add(append_len) > self.max_bytes {
                self.done = true;
                return Some(Err(std::io::Error::new(
                    std::io::ErrorKind::InvalidData,
                    format!("IPC line exceeded {} bytes", self.max_bytes),
                )));
            }
            bytes.extend_from_slice(&available[..append_len]);
            let consumed = newline.map_or(available.len(), |index| index + 1);
            self.reader.consume(consumed);
            if newline.is_some() {
                break;
            }
        }

        if bytes.last() == Some(&b'\r') {
            bytes.pop();
        }
        Some(String::from_utf8(bytes).map_err(|error| {
            std::io::Error::new(
                std::io::ErrorKind::InvalidData,
                format!("IPC line is not valid UTF-8: {error}"),
            )
        }))
    }
}

'''
text = text.replace(marker, helper + marker, 1)

# Java stdout relay in the self-worker: heightmaps legitimately contain a large
# base64 PNG, so use the response-sized bound.
text = replace_once(
    text,
    '''        let reader = BufReader::new(java_stdout);
        let stdout = std::io::stdout();
        let mut output = stdout.lock();
        for line in reader.lines() {''',
    '''        let reader = BufReader::new(java_stdout);
        let stdout = std::io::stdout();
        let mut output = stdout.lock();
        for line in bounded_lines(reader, MAX_ENGINE_STDOUT_LINE_BYTES) {''',
    "Java stdout relay",
)

text = replace_once(
    text,
    '''        let reader = BufReader::new(java_stderr);
        let stderr = std::io::stderr();
        let mut output = stderr.lock();
        for line in reader.lines() {''',
    '''        let reader = BufReader::new(java_stderr);
        let stderr = std::io::stderr();
        let mut output = stderr.lock();
        for line in bounded_lines(reader, MAX_ENGINE_STDERR_LINE_BYTES) {''',
    "Java stderr relay",
)

text = replace_once(
    text,
    '''        let reader = BufReader::new(std::io::stdin());
        for line in reader.lines() {''',
    '''        let reader = BufReader::new(std::io::stdin());
        for line in bounded_lines(reader, MAX_ENGINE_REQUEST_LINE_BYTES) {''',
    "worker stdin relay",
)

# GUI reader of the isolated backend worker.
text = replace_once(
    text,
    '''        let reader = BufReader::new(stdout);
        let mut last_progress: Option<(String, String, i32, String)> = None;
        for line in reader.lines() {''',
    '''        let reader = BufReader::new(stdout);
        let mut last_progress: Option<(String, String, i32, String)> = None;
        for line in bounded_lines(reader, MAX_ENGINE_STDOUT_LINE_BYTES) {''',
    "GUI stdout reader",
)

text = replace_once(
    text,
    '''        for line in BufReader::new(stderr).lines() {''',
    '''        for line in bounded_lines(BufReader::new(stderr), MAX_ENGINE_STDERR_LINE_BYTES) {''',
    "GUI stderr reader",
)

marker = '''    #[test]
    fn java_major_parser_handles_legacy_and_modern_version_strings() {'''
if text.count(marker) != 1:
    raise SystemExit("IPC test insertion anchor changed")
tests = r'''    #[test]
    fn bounded_ipc_lines_preserve_fragmented_crlf_records() {
        let source = std::io::Cursor::new(b"alpha\r\nbeta\ngamma".to_vec());
        let reader = BufReader::with_capacity(3, source);
        let records = bounded_lines(reader, 16)
            .collect::<std::io::Result<Vec<_>>>()
            .unwrap();
        assert_eq!(records, vec!["alpha", "beta", "gamma"]);
    }

    #[test]
    fn bounded_ipc_lines_reject_oversized_records() {
        let source = std::io::Cursor::new(b"123456789\nnext\n".to_vec());
        let reader = BufReader::with_capacity(4, source);
        let mut records = bounded_lines(reader, 8);
        let error = records.next().unwrap().unwrap_err();
        assert_eq!(error.kind(), std::io::ErrorKind::InvalidData);
        assert!(error.to_string().contains("exceeded 8 bytes"));
        assert!(records.next().is_none());
    }

    #[test]
    fn stdout_ipc_limit_covers_heightmap_raster_budget() {
        const HEIGHTMAP_PIXELS: usize = 16 * 1024 * 1024;
        let raw_rgb_bytes = HEIGHTMAP_PIXELS * 3;
        let worst_case_base64 = raw_rgb_bytes.div_ceil(3) * 4;
        assert!(MAX_ENGINE_STDOUT_LINE_BYTES > worst_case_base64 + 1024 * 1024);
    }

'''
text = text.replace(marker, tests + marker, 1)
path.write_text(text, encoding="utf-8")
