@echo off
setlocal EnableExtensions

echo ============================================
echo  Minesport Build Script
echo ============================================
echo Target: Windows / amd64
echo.

echo [1/3] Building Fabric bridge mod...
cd bridge
call gradlew.bat jar
if errorlevel 1 (echo ERROR: Bridge build failed!& cd ..& pause& exit /b 1)
echo Bridge mod built: bridge\build\libs\
cd ..
echo.

echo [2/3] Building Java engine...
cd engine
call gradlew.bat jar
if errorlevel 1 (echo ERROR: Java build failed!& cd ..& pause& exit /b 1)
for %%F in (build\libs\minesport-engine-*.jar) do if not defined ENGINE_JAR set "ENGINE_JAR=%%~fF"
cd ..
echo Java engine built: engine\build\libs\
echo.

if not defined ENGINE_JAR (
    echo ERROR: Java engine JAR was not produced!
    pause
    exit /b 1
)

echo [3/3] Building Go wrapper...
cd wrapper
go mod tidy
if errorlevel 1 (echo ERROR: go mod tidy failed!& cd ..& pause& exit /b 1)

echo   -^> embedding Java engine into Minesport...
go run ./cmd/embed-engine -input "%ENGINE_JAR%" -output embedded_engine_generated.go
if errorlevel 1 (echo ERROR: engine embedding failed!& cd ..& pause& exit /b 1)

where gcc >nul 2>nul
if errorlevel 1 if not defined CC echo WARNING: No C compiler found. Fyne requires CGO.

go build -tags minesport_embedded_engine -trimpath -ldflags="-H windowsgui -s -w" -o minesport.exe .
if errorlevel 1 (echo ERROR: Go build failed!& cd ..& pause& exit /b 1)
cd ..
echo.
echo ============================================
echo  Build complete!
echo ============================================
echo  wrapper\minesport.exe       (engine embedded)
echo  bridge\build\libs\*.jar    (Bridge mod)
echo.
echo  Run: cd wrapper ^&^& .\minesport.exe
echo  Java engine: .\minesport.exe --java-e
echo  Diagnostics: wrapper\minesport.log
echo ============================================
pause
