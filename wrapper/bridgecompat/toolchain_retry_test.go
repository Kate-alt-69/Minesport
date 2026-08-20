package bridgecompat

import (
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"testing"
)

type interruptingBody struct {
	data []byte
	read bool
}

func (body *interruptingBody) Read(buffer []byte) (int, error) {
	if body.read {
		return 0, errors.New("simulated connection reset during body")
	}
	body.read = true
	n := copy(buffer, body.data)
	return n, nil
}

func (body *interruptingBody) Close() error { return nil }

func TestDownloadFileRetriesInterruptedBodies(t *testing.T) {
	originalTransport := http.DefaultTransport
	defer func() { http.DefaultTransport = originalTransport }()

	calls := 0
	http.DefaultTransport = roundTripFunc(func(request *http.Request) (*http.Response, error) {
		calls++
		if calls < 3 {
			return &http.Response{
				StatusCode: http.StatusOK,
				Status:     "200 OK",
				Header:     make(http.Header),
				Body:       &interruptingBody{data: []byte("partial")},
				Request:    request,
			}, nil
		}
		return &http.Response{
			StatusCode: http.StatusOK,
			Status:     "200 OK",
			Header:     make(http.Header),
			Body:       io.NopCloser(stringsReader("complete-archive")),
			Request:    request,
		}, nil
	})

	destination := filepath.Join(t.TempDir(), "jdk.zip")
	if err := downloadFile("https://example.com/jdk.zip", destination); err != nil {
		t.Fatal(err)
	}
	if calls != 3 {
		t.Fatalf("expected three full archive attempts, got %d", calls)
	}
	contents, err := os.ReadFile(destination)
	if err != nil {
		t.Fatal(err)
	}
	if string(contents) != "complete-archive" {
		t.Fatalf("unexpected final archive contents %q", contents)
	}
}

type stringReader struct {
	value string
	offset int
}

func stringsReader(value string) *stringReader { return &stringReader{value: value} }

func (reader *stringReader) Read(buffer []byte) (int, error) {
	if reader.offset >= len(reader.value) {
		return 0, io.EOF
	}
	n := copy(buffer, reader.value[reader.offset:])
	reader.offset += n
	return n, nil
}
