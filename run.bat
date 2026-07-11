@echo off
setlocal enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"

rem Find JDK installed by bootstrap.bat in deps\jdk\
set "JAVA=%SCRIPT_DIR%deps\jdk\bin\java.exe"
if not exist "%JAVA%" (
  echo No JDK found. Run bootstrap.bat first.
  exit /b 1
)

set "FAT_JAR=%SCRIPT_DIR%build\libs\trulyfreeocr.jar"
if not exist "%FAT_JAR%" (
  set "JAVA_HOME=%SCRIPT_DIR%deps\jdk"
  "%SCRIPT_DIR%gradlew.bat" build
)

rem Use Windows-native paths (override Linux defaults in settings.jsonc)
set "NATIVE_DIR=%SCRIPT_DIR%deps\jbig2enc\win"
set "TESSERACT_DIR=%SCRIPT_DIR%deps\tesseract\win"
set "TESSDATA_DIR=%SCRIPT_DIR%deps\tesseract\tessdata"

"%JAVA%" -jar "%FAT_JAR%" --native-dir "%NATIVE_DIR%" --tesseract-path "%TESSERACT_DIR%\tesseract.bat" --tessdata-dir "%TESSDATA_DIR%" %*
