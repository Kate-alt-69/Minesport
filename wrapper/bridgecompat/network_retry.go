package bridgecompat

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

const downloadAttemptCount = 3
const githubContentsLimit = 64 << 20

type retryTransport struct {
	base    http.RoundTripper
	backoff func(int) time.Duration
}

func init() {
	// All bridge compatibility HTTP clients use a nil Transport, which delegates
	// to http.DefaultTransport. Wrapping it here hardens manifest/source/module/JDK
	// downloads without forcing each caller to implement its own retry loop.
	if _, alreadyWrapped := http.DefaultTransport.(*retryTransport); !alreadyWrapped {
		http.DefaultTransport = &retryTransport{base: http.DefaultTransport}
	}
}

func (transport *retryTransport) RoundTrip(request *http.Request) (*http.Response, error) {
	base := transport.base
	if base == nil {
		base = http.DefaultTransport
	}
	if request.Method != http.MethodGet && request.Method != http.MethodHead {
		return base.RoundTrip(request)
	}

	response, err := transport.roundTripAttempts(base, request)
	if err == nil {
		return response, nil
	}

	// Raw GitHub is occasionally unreachable on otherwise healthy connections
	// (TLS handshake resets are the common Windows report). After the normal
	// retries are exhausted, fetch the exact same repository file through the
	// api.github.com Contents endpoint and return its decoded bytes as if Raw had
	// served them directly.
	if strings.EqualFold(request.URL.Hostname(), "raw.githubusercontent.com") {
		fallback, fallbackErr := transport.githubContentsFallback(base, request)
		if fallbackErr == nil {
			return fallback, nil
		}
		return nil, fmt.Errorf("%w; GitHub Contents fallback failed: %v", err, fallbackErr)
	}

	return nil, err
}

func (transport *retryTransport) roundTripAttempts(base http.RoundTripper, request *http.Request) (*http.Response, error) {
	var lastErr error
	for attempt := 1; attempt <= downloadAttemptCount; attempt++ {
		clone := request.Clone(request.Context())
		response, err := base.RoundTrip(clone)
		if err == nil && !retryableHTTPStatus(response.StatusCode) {
			return response, nil
		}

		if response != nil {
			lastErr = fmt.Errorf("HTTP %d from %s", response.StatusCode, request.URL.String())
			drainAndClose(response.Body)
		} else if err != nil {
			lastErr = fmt.Errorf("request %s: %w", request.URL.String(), err)
		}

		if attempt < downloadAttemptCount {
			if err := sleepRequest(request, transport.retryDelay(attempt)); err != nil {
				return nil, err
			}
		}
	}
	if lastErr == nil {
		lastErr = fmt.Errorf("request %s failed after %d attempts", request.URL.String(), downloadAttemptCount)
	}
	return nil, fmt.Errorf("%w (after %d attempts)", lastErr, downloadAttemptCount)
}

func (transport *retryTransport) retryDelay(attempt int) time.Duration {
	if transport.backoff != nil {
		return transport.backoff(attempt)
	}
	// Short enough to keep the UI responsive, long enough to ride out transient
	// TLS/socket failures. attempt=1 => 400ms, attempt=2 => 800ms.
	return time.Duration(attempt) * 400 * time.Millisecond
}

func retryableHTTPStatus(status int) bool {
	return status == http.StatusRequestTimeout ||
		status == http.StatusTooEarly ||
		status == http.StatusTooManyRequests ||
		status >= 500
}

func sleepRequest(request *http.Request, delay time.Duration) error {
	if delay <= 0 {
		return nil
	}
	timer := time.NewTimer(delay)
	defer timer.Stop()
	select {
	case <-timer.C:
		return nil
	case <-request.Context().Done():
		return request.Context().Err()
	}
}

func drainAndClose(body io.ReadCloser) {
	if body == nil {
		return
	}
	_, _ = io.CopyN(io.Discard, body, 32<<10)
	_ = body.Close()
}

func (transport *retryTransport) githubContentsFallback(base http.RoundTripper, rawRequest *http.Request) (*http.Response, error) {
	owner, repository, ref, repoPath, err := parseRawGitHubPath(rawRequest.URL)
	if err != nil {
		return nil, err
	}

	escapedPath := escapeRepositoryPath(repoPath)
	endpoint := fmt.Sprintf(
		"https://api.github.com/repos/%s/%s/contents/%s?ref=%s",
		url.PathEscape(owner),
		url.PathEscape(repository),
		escapedPath,
		url.QueryEscape(ref),
	)
	fallbackURL, err := url.Parse(endpoint)
	if err != nil {
		return nil, err
	}

	fallbackRequest := rawRequest.Clone(rawRequest.Context())
	fallbackRequest.URL = fallbackURL
	fallbackRequest.Host = ""
	fallbackRequest.Header = rawRequest.Header.Clone()
	fallbackRequest.Header.Set("Accept", "application/vnd.github+json")
	fallbackRequest.Header.Set("X-GitHub-Api-Version", "2022-11-28")

	response, err := transport.roundTripAttempts(base, fallbackRequest)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()

	payloadBytes, err := io.ReadAll(io.LimitReader(response.Body, githubContentsLimit+1))
	if err != nil {
		return nil, err
	}
	if len(payloadBytes) > githubContentsLimit {
		return nil, fmt.Errorf("GitHub Contents response exceeded %d bytes", githubContentsLimit)
	}
	var payload struct {
		Content  string `json:"content"`
		Encoding string `json:"encoding"`
	}
	if err := json.Unmarshal(payloadBytes, &payload); err != nil {
		return nil, fmt.Errorf("decode GitHub Contents response: %w", err)
	}
	if payload.Encoding != "base64" || payload.Content == "" {
		return nil, fmt.Errorf("GitHub Contents response did not contain base64 file content")
	}
	decoded, err := base64.StdEncoding.DecodeString(strings.ReplaceAll(payload.Content, "\n", ""))
	if err != nil {
		return nil, fmt.Errorf("decode GitHub Contents file: %w", err)
	}

	header := make(http.Header)
	header.Set("Content-Length", strconv.Itoa(len(decoded)))
	header.Set("Content-Type", "application/octet-stream")
	return &http.Response{
		Status:        "200 OK",
		StatusCode:    http.StatusOK,
		Proto:         "HTTP/1.1",
		ProtoMajor:    1,
		ProtoMinor:    1,
		Header:        header,
		Body:          io.NopCloser(bytes.NewReader(decoded)),
		ContentLength: int64(len(decoded)),
		Request:       rawRequest,
	}, nil
}

func parseRawGitHubPath(rawURL *url.URL) (owner, repository, ref, repoPath string, err error) {
	// Use EscapedPath so an escaped slash inside a ref remains distinguishable
	// from path separators. Minesport currently uses branch-like refs such as
	// "main"; this still safely handles percent-escaped characters.
	parts := strings.SplitN(strings.TrimPrefix(rawURL.EscapedPath(), "/"), "/", 4)
	if len(parts) != 4 {
		return "", "", "", "", fmt.Errorf("unexpected Raw GitHub path: %s", rawURL.Path)
	}
	owner, err = url.PathUnescape(parts[0])
	if err != nil {
		return "", "", "", "", err
	}
	repository, err = url.PathUnescape(parts[1])
	if err != nil {
		return "", "", "", "", err
	}
	ref, err = url.PathUnescape(parts[2])
	if err != nil {
		return "", "", "", "", err
	}
	repoPath, err = url.PathUnescape(parts[3])
	if err != nil {
		return "", "", "", "", err
	}
	if owner == "" || repository == "" || ref == "" || repoPath == "" {
		return "", "", "", "", fmt.Errorf("incomplete Raw GitHub path")
	}
	return owner, repository, ref, repoPath, nil
}

func escapeRepositoryPath(repoPath string) string {
	parts := strings.Split(repoPath, "/")
	for index := range parts {
		parts[index] = url.PathEscape(parts[index])
	}
	return strings.Join(parts, "/")
}
