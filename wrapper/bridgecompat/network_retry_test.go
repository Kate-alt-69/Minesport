package bridgecompat

import (
	"errors"
	"io"
	"net/http"
	"strings"
	"sync"
	"testing"
	"time"
)

type roundTripFunc func(*http.Request) (*http.Response, error)

func (fn roundTripFunc) RoundTrip(request *http.Request) (*http.Response, error) {
	return fn(request)
}

func response(status int, body string, request *http.Request) *http.Response {
	return &http.Response{
		StatusCode: status,
		Status:     http.StatusText(status),
		Header:     make(http.Header),
		Body:       io.NopCloser(strings.NewReader(body)),
		Request:    request,
	}
}

func TestRetryTransportRetriesNetworkFailureTwice(t *testing.T) {
	calls := 0
	base := roundTripFunc(func(request *http.Request) (*http.Response, error) {
		calls++
		if calls < 3 {
			return nil, errors.New("remote error: tls: handshake failure")
		}
		return response(http.StatusOK, "ok", request), nil
	})
	transport := &retryTransport{base: base, backoff: func(int) time.Duration { return 0 }}
	request, _ := http.NewRequest(http.MethodGet, "https://example.com/file", nil)
	resp, err := transport.RoundTrip(request)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if calls != 3 {
		t.Fatalf("expected 3 attempts, got %d", calls)
	}
}

func TestRetryTransportRetriesTransientHTTPStatus(t *testing.T) {
	calls := 0
	base := roundTripFunc(func(request *http.Request) (*http.Response, error) {
		calls++
		if calls < 3 {
			return response(http.StatusServiceUnavailable, "retry", request), nil
		}
		return response(http.StatusOK, "ok", request), nil
	})
	transport := &retryTransport{base: base, backoff: func(int) time.Duration { return 0 }}
	request, _ := http.NewRequest(http.MethodGet, "https://example.com/file", nil)
	resp, err := transport.RoundTrip(request)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if calls != 3 {
		t.Fatalf("expected 3 attempts, got %d", calls)
	}
}

func TestRetryTransportDoesNotRetryPermanentHTTPError(t *testing.T) {
	calls := 0
	base := roundTripFunc(func(request *http.Request) (*http.Response, error) {
		calls++
		return response(http.StatusNotFound, "missing", request), nil
	})
	transport := &retryTransport{base: base, backoff: func(int) time.Duration { return 0 }}
	request, _ := http.NewRequest(http.MethodGet, "https://example.com/file", nil)
	resp, err := transport.RoundTrip(request)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if calls != 1 {
		t.Fatalf("expected 1 attempt for HTTP 404, got %d", calls)
	}
}

func TestRawGitHubFallsBackToContentsAPI(t *testing.T) {
	var mu sync.Mutex
	rawCalls := 0
	apiCalls := 0
	base := roundTripFunc(func(request *http.Request) (*http.Response, error) {
		mu.Lock()
		defer mu.Unlock()
		switch request.URL.Hostname() {
		case "raw.githubusercontent.com":
			rawCalls++
			return nil, errors.New("tls handshake timeout")
		case "api.github.com":
			apiCalls++
			if request.URL.Path != "/repos/Kate-alt-69/Minesport/contents/bridge-versions/manifest.json" {
				t.Fatalf("unexpected fallback path %s", request.URL.Path)
			}
			if request.URL.Query().Get("ref") != "main" {
				t.Fatalf("unexpected fallback ref %q", request.URL.Query().Get("ref"))
			}
			return response(http.StatusOK, `{"content":"aGVsbG8=","encoding":"base64"}`, request), nil
		default:
			t.Fatalf("unexpected host %s", request.URL.Hostname())
			return nil, nil
		}
	})
	transport := &retryTransport{base: base, backoff: func(int) time.Duration { return 0 }}
	request, _ := http.NewRequest(http.MethodGet,
		"https://raw.githubusercontent.com/Kate-alt-69/Minesport/main/bridge-versions/manifest.json", nil)
	resp, err := transport.RoundTrip(request)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatal(err)
	}
	if string(body) != "hello" {
		t.Fatalf("fallback returned %q", body)
	}
	if rawCalls != 3 {
		t.Fatalf("expected 3 Raw GitHub attempts, got %d", rawCalls)
	}
	if apiCalls != 1 {
		t.Fatalf("expected one successful Contents API request, got %d", apiCalls)
	}
}
