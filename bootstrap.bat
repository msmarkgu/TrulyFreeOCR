@echo off
setlocal enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"

rem ── 1. Download OpenJDK 21 LTS for Windows ────────────────────────────
set "JDK_DIR=%SCRIPT_DIR%deps\jdk"
if not exist "%JDK_DIR%" mkdir "%JDK_DIR%"

set "FORCE_DOWNLOAD=false"
set "PADDLE=false"
set "PADDLE_TIER=medium"
for %%a in (%*) do (
  if /i "%%a"=="--force" set "FORCE_DOWNLOAD=true"
  if /i "%%a"=="--paddle" set "PADDLE=true"
  set "ARG=%%a"
  if not "!ARG:--paddle-tier==!"=="%%a" (
    for /f "tokens=2 delims==" %%v in ("%%a") do set "PADDLE_TIER=%%v"
  )
)

set "PADDLEOCR_DIR=%SCRIPT_DIR%deps\paddleocr"
if "%FORCE_DOWNLOAD%"=="true" (
  echo Forcing re-download of all dependencies...
  if exist "%JDK_DIR%" rmdir /S /Q "%JDK_DIR%" 2>nul
  if exist "%TESSDATA_DIR%" rmdir /S /Q "%TESSDATA_DIR%" 2>nul
  if exist "%TESSERACT_DIR%" rmdir /S /Q "%TESSERACT_DIR%" 2>nul
  if exist "%PADDLEOCR_DIR%" rmdir /S /Q "%PADDLEOCR_DIR%" 2>nul
)

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

rem ── 2. Download Gradle 8.0.1 ───────────────────────────────────────────
set "GRADLE_DIR=%SCRIPT_DIR%deps\gradle"
set "GRADLE_VERSION=8.0.1"
if "%FORCE_DOWNLOAD%"=="true" (
  if exist "%GRADLE_DIR%" rmdir /S /Q "%GRADLE_DIR%" 2>nul
)
if not exist "%GRADLE_DIR%\bin\gradle.bat" (
  echo Downloading Gradle %GRADLE_VERSION% for Windows...
  if not exist "%GRADLE_DIR%" mkdir "%GRADLE_DIR%"
  powershell -NoProfile -Command "Invoke-WebRequest -UseBasicParsing -Uri 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '%SCRIPT_DIR%gradle-bin.zip'"
  powershell -NoProfile -Command "Expand-Archive -Path '%SCRIPT_DIR%gradle-bin.zip' -DestinationPath '%SCRIPT_DIR%deps'"
  del "%SCRIPT_DIR%gradle-bin.zip"
  rem Flatten versioned folder
  for /d %%d in ("%SCRIPT_DIR%deps\gradle-%GRADLE_VERSION%") do (
    if exist "%%d" (
      xcopy /E /Y "%%d\*" "%GRADLE_DIR%\" >nul
      rmdir /S /Q "%%d"
    )
  )
  if exist "%GRADLE_DIR%\bin\gradle.bat" echo Gradle %GRADLE_VERSION% downloaded to %GRADLE_DIR%
) else (
  echo Gradle already present
)

rem ── 3. Download Tesseract language data ───────────────────────────────
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

rem Create Tesseract TSV config file (required for OCR output)
if not exist "%TESSDATA_DIR%\configs" mkdir "%TESSDATA_DIR%\configs"
if not exist "%TESSDATA_DIR%\configs\tsv" (
  echo tessedit_create_tsv 1> "%TESSDATA_DIR%\configs\tsv"
  echo Created tessdata/configs/tsv
)

rem ── 4. Download Tesseract for Windows ─────────────────────────────────
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

rem ── 5. jbig2enc — not available as precompiled Windows binary ──────────
rem JBIG2 compression will be unavailable; CCITT G4 fallback is used.
set "JBIG2ENC_DIR=%SCRIPT_DIR%deps\jbig2enc\win"
if not exist "%JBIG2ENC_DIR%" mkdir "%JBIG2ENC_DIR%"
if not exist "%JBIG2ENC_DIR%\jbig2enc.exe" (
  echo NOTE: jbig2enc is not available for Windows.
  echo       JBIG2 compression disabled; using CCITT G4 fallback.
)

rem ── 6. Create tesseract wrapper batch ──────────────────────────────────
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

rem ── 7. Download PP-OCRv6 ONNX models for PaddleOCR engine (optional) ──

rem Helper: download a single tier's models
if "%PADDLE%"=="true" goto :do_paddle
goto :paddle_done

:download_tier
set "TIER=%~1"
set "TIER_DIR=%PADDLEOCR_DIR%\%TIER%"
set "DET_URL=https://huggingface.co/PaddlePaddle/PP-OCRv6_%TIER%_det_onnx/resolve/main/inference.onnx"
set "REC_URL=https://huggingface.co/PaddlePaddle/PP-OCRv6_%TIER%_rec_onnx/resolve/main/inference.onnx"

if not exist "%TIER_DIR%" mkdir "%TIER_DIR%"

if not exist "%TIER_DIR%\det.onnx" (
  echo Downloading PP-OCRv6_%TIER%_det ONNX model...
  powershell -NoProfile -Command "Invoke-WebRequest -UseBasicParsing -Uri '%DET_URL%' -OutFile '%TIER_DIR%\det.onnx'"
  echo Detection model downloaded
) else (
  echo PP-OCRv6_%TIER% detection model already present
)

if not exist "%TIER_DIR%\rec.onnx" (
  echo Downloading PP-OCRv6_%TIER%_rec ONNX model...
  powershell -NoProfile -Command "Invoke-WebRequest -UseBasicParsing -Uri '%REC_URL%' -OutFile '%TIER_DIR%\rec.onnx'"
  echo Recognition model downloaded
) else (
  echo PP-OCRv6_%TIER% recognition model already present
)
exit /b

:do_paddle
if not exist "%PADDLEOCR_DIR%" mkdir "%PADDLEOCR_DIR%"

if "%PADDLE_TIER%"=="all" (
  call :download_tier tiny
  call :download_tier small
  call :download_tier medium
) else if "%PADDLE_TIER%"=="tiny" (
  call :download_tier tiny
) else if "%PADDLE_TIER%"=="small" (
  call :download_tier small
) else if "%PADDLE_TIER%"=="medium" (
  call :download_tier medium
) else (
  echo ERROR: Unknown paddle tier '%PADDLE_TIER%'. Use tiny, small, medium, or all.
  goto :eof
)

rem Download character dictionary (shared across all tiers)
if not exist "%PADDLEOCR_DIR%\ppocr_keys_v6.txt" (
  echo Downloading PP-OCRv6 character dictionary (18708 chars)...
  powershell -NoProfile -Command "Invoke-WebRequest -UseBasicParsing -Uri 'https://raw.githubusercontent.com/PaddlePaddle/PaddleOCR/main/ppocr/utils/dict/ppocrv6_dict.txt' -OutFile '%PADDLEOCR_DIR%\ppocr_keys_v6.txt'"
  echo Character dictionary downloaded
) else (
  echo Character dictionary already present
)

rem Download PP-OCRv5 English-specific recognition model (7.5 MB, 436-char dict)
rem Activated via --language en
set "LANG_DIR=%PADDLEOCR_DIR%\languages\en"
if not exist "%LANG_DIR%" mkdir "%LANG_DIR%"
if not exist "%LANG_DIR%\rec.onnx" (
  echo Downloading PP-OCRv5 English-specific rec ONNX model (7.5 MB)...
  powershell -NoProfile -Command "Invoke-WebRequest -UseBasicParsing -Uri 'https://huggingface.co/monkt/paddleocr-onnx/resolve/main/languages/english/rec.onnx' -OutFile '%LANG_DIR%\rec.onnx'"
  echo English rec model downloaded
) else (
  echo PP-OCRv5 English rec model already present
)

set "DICT_DIR=%PADDLEOCR_DIR%\dict"
if not exist "%DICT_DIR%" mkdir "%DICT_DIR%"
if not exist "%DICT_DIR%\en_dict.txt" (
  echo Downloading PP-OCRv5 English character dictionary (436 chars)...
  powershell -NoProfile -Command "Invoke-WebRequest -UseBasicParsing -Uri 'https://huggingface.co/monkt/paddleocr-onnx/resolve/main/languages/english/dict.txt' -OutFile '%DICT_DIR%\en_dict.txt'"
  echo English character dictionary downloaded
) else (
  echo PP-OCRv5 English dict already present
)

:paddle_done

rem ── 8. Done ───────────────────────────────────────────────────────────
echo.
echo Bootstrap complete
echo Run:  run.bat input.pdf -o output.pdf
echo Build: gradlew build
echo.
echo Flags: --force                    force re-download of all dependencies
echo        --paddle                   download PP-OCRv6 ONNX models for --ocr-engine paddle
echo        --paddle-tier={tier}       model size: tiny (~6 MB), small (~25 MB), medium (~132 MB), or all

endlocal
