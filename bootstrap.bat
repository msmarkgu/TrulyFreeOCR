@echo off
setlocal enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"

rem ── 1. Download OpenJDK 21 LTS for Windows ────────────────────────────
set "JDK_DIR=%SCRIPT_DIR%deps\jdk"
if not exist "%JDK_DIR%" mkdir "%JDK_DIR%"

set "FORCE_DOWNLOAD=false"
if /i "%~1"=="--force" set "FORCE_DOWNLOAD=true"

rem Check if JDK already present
set "JAVA_EXE="
if exist "%JDK_DIR%\bin\java.exe" set "JAVA_EXE=%JDK_DIR%\bin\java.exe"

if not defined JAVA_EXE (
  echo Downloading OpenJDK 21 for Windows x64...
  powershell -NoProfile -Command "Invoke-WebRequest -UseBasicParsing -Uri 'https://api.adoptium.net/v3/binary/latest/21/ga/win/x64/jdk/hotspot/normal/eclipse' -OutFile '%SCRIPT_DIR%openjdk21-win.zip'"
  powershell -NoProfile -Command "Expand-Archive -Path '%SCRIPT_DIR%openjdk21-win.zip' -DestinationPath '%JDK_DIR%'"
  del "%SCRIPT_DIR%openjdk21-win.zip"
  rem Flatten versioned folder (jdk-*) so java.exe is at %JDK_DIR%\bin\java.exe
  for /d %%d in ("%JDK_DIR%\jdk-*") do (
    xcopy /E /Y "%%d\*" "%JDK_DIR%\" >nul
    rmdir /S /Q "%%d"
  )
  if exist "%JDK_DIR%\bin\java.exe" set "JAVA_EXE=%JDK_DIR%\bin\java.exe"
  echo OpenJDK 21 downloaded to %JDK_DIR%
) else (
  echo OpenJDK already present
)

rem ── 2. Download Tesseract language data ───────────────────────────────
set "TESSDATA_DIR=%SCRIPT_DIR%deps\tesseract\tessdata"
if not exist "%TESSDATA_DIR%" mkdir "%TESSDATA_DIR%"

for %%l in (eng fra deu spa chi_sim chi_tra jpn osd) do (
  if not exist "%TESSDATA_DIR%\%%l.traineddata" (
    echo Downloading %%l.traineddata...
    powershell -NoProfile -Command "Invoke-WebRequest -UseBasicParsing -Uri 'https://github.com/tesseract-ocr/tessdata/raw/main/%%l.traineddata' -OutFile '%TESSDATA_DIR%\%%l.traineddata'"
  ) else (
    echo %%l.traineddata already present
  )
)
echo Tessdata downloaded to %TESSDATA_DIR%

rem ── 3. Download Tesseract for Windows ─────────────────────────────────
set "TESSERACT_DIR=%SCRIPT_DIR%deps\tesseract\win"
if not exist "%TESSERACT_DIR%" mkdir "%TESSERACT_DIR%"

if not exist "%TESSERACT_DIR%\tesseract.exe" (
  echo Downloading Tesseract for Windows...
  powershell -NoProfile -Command "Invoke-WebRequest -UseBasicParsing -Uri 'https://digi.bib.uni-mannheim.de/tesseract/tesseract-ocr-w64-portable-5.3.3.20231005.zip' -OutFile '%SCRIPT_DIR%tesseract-win.zip'"
  powershell -NoProfile -Command "Expand-Archive -Path '%SCRIPT_DIR%tesseract-win.zip' -DestinationPath '%TESSERACT_DIR%'"
  del "%SCRIPT_DIR%tesseract-win.zip"
  rem Flatten nested directory (portable zip wraps in versioned folder)
  for /d %%d in ("%TESSERACT_DIR%\tesseract-ocr-w64-portable-*") do (
    xcopy /E /Y "%%d\*" "%TESSERACT_DIR%\" >nul
    rmdir /S /Q "%%d"
  )
  echo Tesseract installed to %TESSERACT_DIR%
) else (
  echo Tesseract already present
)

rem Ensure lib directory exists for DLLs
if not exist "%TESSERACT_DIR%\lib" mkdir "%TESSERACT_DIR%\lib"

rem ── 4. jbig2enc — not available as precompiled Windows binary ──────────
rem JBIG2 compression will be unavailable; CCITT G4 fallback is used.
set "JBIG2ENC_DIR=%SCRIPT_DIR%deps\jbig2enc\win"
if not exist "%JBIG2ENC_DIR%" mkdir "%JBIG2ENC_DIR%"
if not exist "%JBIG2ENC_DIR%\jbig2enc.exe" (
  echo NOTE: jbig2enc is not available for Windows.
  echo       JBIG2 compression disabled; using CCITT G4 fallback.
)

rem ── 5. Create tesseract wrapper batch ──────────────────────────────────
rem The wrapper sets TESSDATA_PREFIX and adds dependencies to PATH
rem before delegating to the portable tesseract.exe.
if not exist "%TESSERACT_DIR%\tesseract.bat" (
  (
    echo @echo off
    echo set "SCRIPT_DIR=%%~dp0"
    echo set "TESSDATA_PREFIX=%%SCRIPT_DIR%%..\tessdata"
    echo set "PATH=%%SCRIPT_DIR%%;%%SCRIPT_DIR%%lib;%%PATH%%"
    echo "%%SCRIPT_DIR%%tesseract.exe" %%*
  ) > "%TESSERACT_DIR%\tesseract.bat"
  echo Created tesseract wrapper at %TESSERACT_DIR%\tesseract.bat
)

rem ── 6. Done ───────────────────────────────────────────────────────────
echo.
echo Bootstrap complete
echo Run:  run.bat input.pdf -o output.pdf
if defined JAVA_EXE (
  echo Build: !JAVA_EXE! -jar gradle\wrapper\gradle-wrapper.jar build
)

endlocal
