package bridgecompat

import (
	"archive/tar"
	"archive/zip"
	"compress/gzip"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
	"time"

	"github.com/kastrick/minesport/processutil"
)

func ensureJDK(required int, progress ProgressFunc) (string, error) {
	report(progress, 36, "Preparing…", fmt.Sprintf("Checking for JDK %d", required))
	if home := findInstalledJDK(required); home != "" {
		report(progress, 42, "Preparing…", fmt.Sprintf("Using installed JDK %d", required))
		return home, nil
	}

	report(progress, 42, fmt.Sprintf("Downloading JDK %d…", required), "A compiler is required; Minecraft runtimes do not always include javac")
	home, err := downloadAdoptiumJDK(required)
	if err != nil {
		return "", fmt.Errorf("prepare JDK %d: %w", required, err)
	}
	report(progress, 54, "Preparing…", fmt.Sprintf("JDK %d ready", required))
	return home, nil
}

func findInstalledJDK(required int) string {
	var candidates []string
	if home := os.Getenv("JAVA_HOME"); home != "" {
		candidates = append(candidates, filepath.Join(home, "bin", javacName()))
	}
	if path, err := exec.LookPath("javac"); err == nil {
		candidates = append(candidates, path)
	}
	toolchain := filepath.Join(toolchainRoot(), fmt.Sprintf("jdk-%d", required))
	_ = filepath.WalkDir(toolchain, func(path string, entry os.DirEntry, err error) error {
		if err != nil || entry == nil || entry.IsDir() {
			return nil
		}
		if strings.EqualFold(entry.Name(), javacName()) {
			candidates = append(candidates, path)
		}
		return nil
	})

	seen := map[string]bool{}
	for _, candidate := range candidates {
		candidate = filepath.Clean(candidate)
		if seen[candidate] {
			continue
		}
		seen[candidate] = true
		if javacMajor(candidate) >= required {
			return filepath.Dir(filepath.Dir(candidate))
		}
	}
	return ""
}

func javacMajor(javac string) int {
	cmd := exec.Command(javac, "-version")
	processutil.HideWindow(cmd)
	output, err := cmd.CombinedOutput()
	if err != nil {
		return 0
	}
	rx := regexp.MustCompile(`(?i)javac\s+([0-9]+)`)
	match := rx.FindStringSubmatch(string(output))
	if len(match) < 2 {
		return 0
	}
	var major int
	_, _ = fmt.Sscanf(match[1], "%d", &major)
	return major
}

func downloadAdoptiumJDK(version int) (string, error) {
	osName := runtime.GOOS
	if osName == "darwin" {
		osName = "mac"
	}
	arch := runtime.GOARCH
	switch arch {
	case "amd64":
		arch = "x64"
	case "arm64":
		arch = "aarch64"
	default:
		return "", fmt.Errorf("automatic JDK download does not support architecture %s", runtime.GOARCH)
	}

	endpoint := fmt.Sprintf(
		"https://api.adoptium.net/v3/assets/latest/%d/hotspot?architecture=%s&image_type=jdk&jvm_impl=hotspot&os=%s&vendor=eclipse",
		version,
		arch,
		osName,
	)
	data, err := httpGet(endpoint)
	if err != nil {
		return "", err
	}
	var assets []struct {
		Binary struct {
			Package struct {
				Link     string `json:"link"`
				Checksum string `json:"checksum"`
				Name     string `json:"name"`
			} `json:"package"`
		} `json:"binary"`
	}
	if err := json.Unmarshal(data, &assets); err != nil {
		return "", err
	}
	if len(assets) == 0 || assets[0].Binary.Package.Link == "" {
		return "", fmt.Errorf("Adoptium did not return a JDK %d package", version)
	}
	pkg := assets[0].Binary.Package

	root := filepath.Join(toolchainRoot(), fmt.Sprintf("jdk-%d", version))
	if err := os.RemoveAll(root); err != nil {
		return "", err
	}
	if err := os.MkdirAll(root, 0o755); err != nil {
		return "", err
	}
	archiveName := pkg.Name
	if archiveName == "" {
		if runtime.GOOS == "windows" {
			archiveName = "jdk.zip"
		} else {
			archiveName = "jdk.tar.gz"
		}
	}
	archivePath := filepath.Join(root, archiveName)
	if err := downloadFile(pkg.Link, archivePath); err != nil {
		return "", err
	}
	if pkg.Checksum != "" {
		if err := verifySHA256(archivePath, pkg.Checksum); err != nil {
			return "", err
		}
	}

	extractRoot := filepath.Join(root, "runtime")
	if strings.HasSuffix(strings.ToLower(archivePath), ".zip") {
		if err := extractZip(archivePath, extractRoot); err != nil {
			return "", err
		}
	} else {
		if err := extractTarGz(archivePath, extractRoot); err != nil {
			return "", err
		}
	}
	_ = os.Remove(archivePath)

	var javac string
	_ = filepath.WalkDir(extractRoot, func(path string, entry os.DirEntry, err error) error {
		if err != nil || entry == nil || entry.IsDir() || javac != "" {
			return nil
		}
		if strings.EqualFold(entry.Name(), javacName()) {
			javac = path
		}
		return nil
	})
	if javac == "" {
		return "", fmt.Errorf("downloaded JDK %d did not contain javac", version)
	}
	if javacMajor(javac) < version {
		return "", fmt.Errorf("downloaded javac is older than required JDK %d", version)
	}
	return filepath.Dir(filepath.Dir(javac)), nil
}

func toolchainRoot() string { return filepath.Join(userDataRoot(), "toolchains") }

func javacName() string {
	if runtime.GOOS == "windows" {
		return "javac.exe"
	}
	return "javac"
}

func downloadFile(endpoint, destination string) error {
	client := &http.Client{Timeout: 30 * time.Minute}
	req, err := http.NewRequest(http.MethodGet, endpoint, nil)
	if err != nil {
		return err
	}
	req.Header.Set("User-Agent", "Minesport-Bridge-Builder/1")
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("HTTP %d downloading %s", resp.StatusCode, endpoint)
	}
	if err := os.MkdirAll(filepath.Dir(destination), 0o755); err != nil {
		return err
	}
	file, err := os.Create(destination)
	if err != nil {
		return err
	}
	_, copyErr := io.Copy(file, resp.Body)
	closeErr := file.Close()
	if copyErr != nil {
		return copyErr
	}
	return closeErr
}

func verifySHA256(file, expected string) error {
	input, err := os.Open(file)
	if err != nil {
		return err
	}
	defer input.Close()
	hash := sha256.New()
	if _, err := io.Copy(hash, input); err != nil {
		return err
	}
	actual := hex.EncodeToString(hash.Sum(nil))
	if !strings.EqualFold(strings.TrimSpace(expected), actual) {
		return fmt.Errorf("JDK checksum mismatch: expected %s, got %s", expected, actual)
	}
	return nil
}

func extractZip(archivePath, destination string) error {
	reader, err := zip.OpenReader(archivePath)
	if err != nil {
		return err
	}
	defer reader.Close()
	for _, entry := range reader.File {
		target, err := safeJoin(destination, filepath.FromSlash(entry.Name))
		if err != nil {
			return err
		}
		if entry.FileInfo().IsDir() {
			if err := os.MkdirAll(target, 0o755); err != nil {
				return err
			}
			continue
		}
		if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
			return err
		}
		input, err := entry.Open()
		if err != nil {
			return err
		}
		mode := entry.Mode()
		if mode == 0 {
			mode = 0o644
		}
		output, err := os.OpenFile(target, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, mode)
		if err != nil {
			input.Close()
			return err
		}
		_, copyErr := io.Copy(output, input)
		input.Close()
		closeErr := output.Close()
		if copyErr != nil {
			return copyErr
		}
		if closeErr != nil {
			return closeErr
		}
	}
	return nil
}

func extractTarGz(archivePath, destination string) error {
	file, err := os.Open(archivePath)
	if err != nil {
		return err
	}
	defer file.Close()
	gz, err := gzip.NewReader(file)
	if err != nil {
		return err
	}
	defer gz.Close()
	reader := tar.NewReader(gz)
	for {
		header, err := reader.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			return err
		}
		target, err := safeJoin(destination, filepath.FromSlash(header.Name))
		if err != nil {
			return err
		}
		switch header.Typeflag {
		case tar.TypeDir:
			if err := os.MkdirAll(target, os.FileMode(header.Mode)); err != nil {
				return err
			}
		case tar.TypeReg, tar.TypeRegA:
			if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
				return err
			}
			output, err := os.OpenFile(target, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, os.FileMode(header.Mode))
			if err != nil {
				return err
			}
			_, copyErr := io.Copy(output, reader)
			closeErr := output.Close()
			if copyErr != nil {
				return copyErr
			}
			if closeErr != nil {
				return closeErr
			}
		case tar.TypeSymlink:
			if filepath.IsAbs(header.Linkname) || strings.Contains(filepath.Clean(header.Linkname), ".."+string(filepath.Separator)) {
				continue
			}
			if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
				return err
			}
			_ = os.Symlink(header.Linkname, target)
		}
	}
	return nil
}
