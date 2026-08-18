@echo off
echo ============================================
echo  Minesport Build Script
echo ============================================
echo.

:: -- Fabric bridge mod --------------------------
echo [1/3] Building Fabric bridge mod...
cd bridge
call gradlew.bat jar
if errorlevel 1 (
    echo ERROR: Bridge build failed!
    cd ..
    pause
    exit /b 1
)
echo Bridge mod built: bridge\build\libs\
cd ..
echo.

:: -- Java engine ---------------------------------
echo [2/3] Building Java engine...
cd engine
call gradlew.bat jar
if errorlevel 1 (
    echo ERROR: Java build failed!
    cd ..
    pause
    exit /b 1
)
echo Java engine built: engine\build\libs\

:: Copy jar to wrapper dir for easy distribution
copy /Y build\libs\minesport-engine-*.jar ..\wrapper\
cd ..
echo.

:: -- Go wrapper -----------------------------------
echo [3/3] Building Go wrapper...
cd wrapper

echo   -^> go mod tidy...
call go mod tidy
if errorlevel 1 (
    echo ERROR: go mod tidy failed!
    cd ..
    pause
    exit /b 1
)

:: Fyne's desktop OpenGL backend needs CGO + a real C compiler. Without one,
:: Go silently sets CGO_ENABLED=0 and cgo-based packages (go-gl) fail with a
:: cryptic "build constraints exclude all Go files" instead of a clear
:: "no C compiler found" -- so check for gcc up front and say so plainly.
setlocal enabledelayedexpansion
where gcc >nul 2>nul
if errorlevel 1 (
    if not defined CC (
        echo.
        echo WARNING: No C compiler ^(gcc^) found on PATH.
        echo   Fyne needs CGO + a real C compiler to build its Windows OpenGL backend.
        echo   Quickest fix: grab w64devkit ^(no installer, just unzip^):
        echo     https://github.com/skeeto/w64devkit/releases
        echo   Then add its bin\ folder to PATH and open a NEW terminal before retrying.
        echo   Attempting the build anyway in case a compiler is configured another way...
        echo.
    )
) else (
    if not defined CC (
        for /f "delims=" %%M in ('gcc -dumpmachine 2^>nul') do set GCC_MACHINE=%%M
        echo !GCC_MACHINE! | findstr /I "x86_64 amd64 aarch64" >nul
        if errorlevel 1 (
            echo.
            echo WARNING: gcc reports target "!GCC_MACHINE!" -- looks 32-bit-only.
            echo   Go needs a 64-bit-capable compiler ^(x86_64-w64-mingw32^) for a windows/amd64 build.
            echo   This usually means an older gcc is earlier on PATH than your real one.
            echo   Run "where gcc" to see every match in PATH order, then either reorder PATH so
            echo   the 64-bit one comes first, or bypass PATH entirely with:
            echo     set CC=^<full path to your 64-bit gcc.exe^>
            echo.
        )
    )
)
endlocal

go build -ldflags="-H windowsgui" -o minesport.exe .
if errorlevel 1 (
    echo ERROR: Go build failed!
    cd ..
    pause
    exit /b 1
)
cd ..
echo.

echo ============================================
echo  Build complete!
echo ============================================
echo  Executables:
echo    wrapper\minesport.exe          (Go UI)
echo    wrapper\minesport-engine-*.jar (Java engine)
echo    bridge\build\libs\*.jar        (Bridge mod)
echo.
echo  To run:
echo    cd wrapper ^&^& minesport.exe
echo.
echo  Dev mode (Java UI directly):
echo    cd engine ^&^& gradlew.bat run
echo ============================================
pause
