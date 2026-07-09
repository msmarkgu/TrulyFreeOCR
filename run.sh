#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Detect OS
case "$(uname -s)" in
  Darwin) OS="mac"  ;;
  Linux)  OS="linux" ;;
  *)      echo "Unsupported OS: $(uname -s)"; exit 1 ;;
esac

# Detect arch
case "$(uname -m)" in
  x86_64|amd64)  ARCH="x64" ;;
  aarch64|arm64) ARCH="aarch64" ;;
  *) echo "Unsupported arch: $(uname -m)"; exit 1 ;;
esac

# Resolve TFOCR_JAVA_HOME
if [ -z "${TFOCR_JAVA_HOME:-}" ]; then
  if [ -d "$SCRIPT_DIR/jdk/$OS" ]; then
    # Try versioned subdirectory first, then flat extraction
    JDK_DIR=$(ls -d "$SCRIPT_DIR/jdk/$OS"/jdk-* 2>/dev/null | head -1)
    if [ -z "$JDK_DIR" ]; then
      JDK_DIR="$SCRIPT_DIR/jdk/$OS"
    fi
    if [ "$OS" = "mac" ]; then
      if [ -d "$JDK_DIR/Contents/Home" ]; then
        export TFOCR_JAVA_HOME="$JDK_DIR/Contents/Home"
      else
        export TFOCR_JAVA_HOME="$JDK_DIR"
      fi
    else
      export TFOCR_JAVA_HOME="$JDK_DIR"
    fi
  fi
fi

if [ -z "${TFOCR_JAVA_HOME:-}" ] || [ ! -x "$TFOCR_JAVA_HOME/bin/java" ]; then
  echo "No JDK found. Run bootstrap.sh or set TFOCR_JAVA_HOME."
  exit 1
fi

JAVA="$TFOCR_JAVA_HOME/bin/java"
if [ ! -x "$JAVA" ]; then
  echo "Java not found at $JAVA"
  exit 1
fi

FAT_JAR="$SCRIPT_DIR/build/libs/trulyfreeocr.jar"
if [ ! -f "$FAT_JAR" ]; then
  # Build it
  "$JAVA" -jar "$SCRIPT_DIR/gradle/wrapper/gradle-wrapper.jar" build
fi

exec "$JAVA" -jar "$FAT_JAR" "$@"
