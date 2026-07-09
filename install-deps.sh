#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# ── Platform detection ──────────────────────────────────────────────────────
case "$(uname -s)" in
  Darwin) OS="mac"  ;;
  Linux)  OS="linux" ;;
  *)      echo "Unsupported OS: $(uname -s)"; exit 1 ;;
esac

case "$(uname -m)" in
  x86_64|amd64)  ARCH="x64" ;;
  aarch64|arm64) ARCH="aarch64" ;;
  *) echo "Unsupported arch: $(uname -m)"; exit 1 ;;
esac

echo "Detected: $OS / $ARCH"

# ── 1. Download OpenJDK 21 LTS ──────────────────────────────────────────────
JDK_DIR="$SCRIPT_DIR/jdk/$OS"
mkdir -p "$JDK_DIR"

if [ -z "$(ls -A "$JDK_DIR" 2>/dev/null)" ]; then
  echo "Downloading OpenJDK 21 LTS for $OS/$ARCH..."
  API_URL="https://api.adoptium.net/v3/binary/latest/21/ga/$OS/$ARCH/jdk/hotspot/normal/eclipse"
  case "$OS" in
    mac)  JDK_ARCHIVE="openjdk21-mac.tar.gz" ;;
    linux) JDK_ARCHIVE="openjdk21-linux.tar.gz" ;;
  esac

  curl -fsSL -o "$SCRIPT_DIR/$JDK_ARCHIVE" "$API_URL"
  tar -xzf "$SCRIPT_DIR/$JDK_ARCHIVE" -C "$JDK_DIR" --strip-components=1 2>/dev/null || \
    tar -xzf "$SCRIPT_DIR/$JDK_ARCHIVE" -C "$JDK_DIR"
  rm -f "$SCRIPT_DIR/$JDK_ARCHIVE"
  echo "OpenJDK 21 downloaded to $JDK_DIR"
else
  echo "OpenJDK already present in $JDK_DIR"
fi

# Find actual JDK home
if [ "$OS" = "mac" ]; then
  JAVA_HOME_PATH=$(ls -d "$JDK_DIR"/jdk-*/Contents/Home 2>/dev/null | head -1)
else
  JAVA_HOME_PATH=$(ls -d "$JDK_DIR"/jdk-* 2>/dev/null | head -1)
fi
if [ -z "$JAVA_HOME_PATH" ]; then
  if [ -x "$JDK_DIR/bin/java" ]; then
    JAVA_HOME_PATH="$JDK_DIR"
  else
    echo "Cannot find JDK installation in $JDK_DIR"
    exit 1
  fi
fi
echo "JDK home: $JAVA_HOME_PATH"
export TFOCR_JAVA_HOME="$JAVA_HOME_PATH"

# ── 2. Download Tesseract language data ─────────────────────────────────────
TESSDATA_DIR="$SCRIPT_DIR/tessdata"
mkdir -p "$TESSDATA_DIR"

LANGUAGES="eng fra deu spa chi_sim chi_tra jpn"
for lang in $LANGUAGES; do
  if [ ! -f "$TESSDATA_DIR/${lang}.traineddata" ]; then
    echo "Downloading ${lang}.traineddata..."
    curl -fsSL -o "$TESSDATA_DIR/${lang}.traineddata" \
      "https://github.com/tesseract-ocr/tessdata/raw/main/${lang}.traineddata"
  else
    echo "${lang}.traineddata already present"
  fi
done
echo "Tessdata downloaded to $TESSDATA_DIR (${LANGUAGES})"

# ── 3. Download jbig2enc ────────────────────────────────────────────────────
NATIVE_DIR="$SCRIPT_DIR/native/$OS"
mkdir -p "$NATIVE_DIR"

if [ ! -x "$NATIVE_DIR/jbig2enc" ] && [ "$OS" != "win" ]; then
  echo "Downloading jbig2enc for $OS/$ARCH..."
  case "$OS" in
    linux)
      if command -v cmake &>/dev/null && command -v g++ &>/dev/null; then
        BUILD_DIR=$(mktemp -d)
        (
          cd "$BUILD_DIR"
          git clone --depth=1 https://github.com/agl/jbig2enc.git
          cd jbig2enc
          ./configure && make
          cp jbig2enc "$NATIVE_DIR/"
        )
        rm -rf "$BUILD_DIR"
        echo "jbig2enc built to $NATIVE_DIR/jbig2enc"
      else
        echo "WARNING: cmake/g++ not found. Please install build tools and re-run,"
        echo "or download jbig2enc manually to $NATIVE_DIR/jbig2enc"
      fi
      ;;
    mac)
      if command -v brew &>/dev/null; then
        brew install jbig2enc 2>/dev/null || true
        JBIG_BIN=$(which jbig2enc 2>/dev/null || echo "")
        if [ -n "$JBIG_BIN" ]; then
          cp "$JBIG_BIN" "$NATIVE_DIR/"
          echo "jbig2enc copied from brew to $NATIVE_DIR/jbig2enc"
        fi
      fi
      ;;
  esac
fi

echo ""
echo "── install-deps complete ──"
echo "TFOCR_JAVA_HOME=$TFOCR_JAVA_HOME"
echo ""
echo "Build: $TFOCR_JAVA_HOME/bin/java -jar gradle/wrapper/gradle-wrapper.jar build"
echo "Test:  $TFOCR_JAVA_HOME/bin/java -jar gradle/wrapper/gradle-wrapper.jar test"
