@echo off
setlocal enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"

rem Detect architecture
if "%PROCESSOR_ARCHITECTURE%"=="AMD64" set "ARCH=x64"
if "%PROCESSOR_ARCHITECTURE%"=="ARM64" set "ARCH=aarch64"
if "%PROCESSOR_ARCHITECTURE%"=="x86" set "ARCH=x64"
if "%PROCESSOR_ARCHITECTURE%"=="IA64" set "ARCH=x64"

rem Resolve TFOCR_JAVA_HOME
if "%TFOCR_JAVA_HOME%"=="" (
  if exist "%SCRIPT_DIR%jdk\win\" (
    for /d %%d in ("%SCRIPT_DIR%jdk\win\jdk-*") do (
      if exist "%%d\bin\java.exe" set "TFOCR_JAVA_HOME=%%d"
    )
  )
)

if "%TFOCR_JAVA_HOME%"=="" (
  echo No JDK found in jdk\win\. Run bootstrap.bat or set TFOCR_JAVA_HOME.
  exit /b 1
)

set "JAVA=%TFOCR_JAVA_HOME%\bin\java.exe"
if not exist "%JAVA%" (
  echo Java not found at %JAVA%
  exit /b 1
)

set "FAT_JAR=%SCRIPT_DIR%build\libs\trulyfreeocr.jar"
if not exist "%FAT_JAR%" (
  "%JAVA%" -jar "%SCRIPT_DIR%gradle\wrapper\gradle-wrapper.jar" build
)

"%JAVA%" -jar "%FAT_JAR%" %*
