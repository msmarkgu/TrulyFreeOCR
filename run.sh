#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

JAVA="$SCRIPT_DIR/deps/jdk/bin/java"
if [ ! -x "$JAVA" ]; then
  echo "No JDK found at $JAVA. Run bootstrap.sh first."
  exit 1
fi

FAT_JAR="$SCRIPT_DIR/build/trulyfreeocr.jar"
if [ ! -f "$FAT_JAR" ]; then
  JAVA_HOME="$SCRIPT_DIR/deps/jdk" "$SCRIPT_DIR/gradlew" build
fi

# Detect platform for tesseract path (linux/mac/win)
case "$(uname -s)" in
  Linux*)  _PLATFORM="linux" ;;
  Darwin*) _PLATFORM="mac" ;;
  CYGWIN*|MINGW*|MSYS*) _PLATFORM="win" ;;
  *)       _PLATFORM="" ;;
esac

echo "Using JDK: $JAVA"

if [ -n "$_PLATFORM" ]; then
  exec "$JAVA" -jar "$FAT_JAR" \
    --native-dir "$SCRIPT_DIR/deps/jbig2enc" \
    --tesseract-path "$SCRIPT_DIR/deps/tesseract/$_PLATFORM/tesseract" \
    --tessdata-dir "$SCRIPT_DIR/deps/tesseract/tessdata" \
    "$@"
else
  exec "$JAVA" -jar "$FAT_JAR" "$@"
fi
