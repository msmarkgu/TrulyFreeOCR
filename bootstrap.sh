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
# Add --force flag to force re-download all dependencies
FORCE_DOWNLOAD=false
if [ "$#" -gt 0 ] && [ "$1" = "--force" ]; then
  FORCE_DOWNLOAD=true
  echo "Forcing re-download of all dependencies..."
fi

JDK_DIR="$SCRIPT_DIR/jdk/$OS"
mkdir -p "$JDK_DIR"

if [ "$FORCE_DOWNLOAD" = true ] || [ -z "$(ls -A "$JDK_DIR" 2>/dev/null)" ]; then
  echo "Downloading OpenJDK 21 LTS for $OS/$ARCH..."

  # Adoptium API
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

# Skip JDK download if already present
if [ -d "$JDK_DIR/jdk-21*" ]; then
  echo "OpenJDK 21 already present in $JDK_DIR"
  JDK_DIR="$JDK_DIR/jdk-21*"
fi

# Find actual JDK home
if [ "$OS" = "mac" ]; then
  JAVA_HOME_PATH=$(ls -d "$JDK_DIR"/jdk-*/Contents/Home 2>/dev/null | head -1)
else
  JAVA_HOME_PATH=$(ls -d "$JDK_DIR"/jdk-* 2>/dev/null | head -1)
fi
if [ -z "$JAVA_HOME_PATH" ]; then
  # Try flat extraction
  if [ -x "$JDK_DIR/bin/java" ]; then
    JAVA_HOME_PATH="$JDK_DIR"
  else
    echo "Cannot find JDK installation in $JDK_DIR"
    exit 1
  fi
fi
echo "JDK home: $JAVA_HOME_PATH"

# Set TFOCR_JAVA_HOME for subsequent steps
export TFOCR_JAVA_HOME="$JAVA_HOME_PATH"

# ── 2. Download Tesseract language data ─────────────────────────────────────
TESSDATA_DIR="$SCRIPT_DIR/tessdata"
mkdir -p "$TESSDATA_DIR"

LANGUAGES="eng fra deu spa chi_sim chi_tra jpn"
for lang in $LANGUAGES; do
  if [ "$FORCE_DOWNLOAD" = true ] || [ ! -f "$TESSDATA_DIR/${lang}.traineddata" ]; then
    echo "Downloading ${lang}.traineddata..."
    curl -fsSL -o "$TESSDATA_DIR/${lang}.traineddata" \
      "https://github.com/tesseract-ocr/tessdata/raw/main/${lang}.traineddata"
  else
    echo "${lang}.traineddata already present"
  fi
done
echo "Tessdata downloaded to $TESSDATA_DIR (${LANGUAGES})"

# ── 3. Set up native binaries ───────────────────────────────────────────────
NATIVE_DIR="$SCRIPT_DIR/native/$OS"
mkdir -p "$NATIVE_DIR"

case "$OS" in
  linux)
    echo "Checking system Tesseract/Leptonica..."
    if ! command -v tesseract &>/dev/null; then
      echo "Installing tesseract-ocr..."
      apt-get update && apt-get install -y tesseract-ocr libleptonica-dev
    else
      echo "Tesseract found: $(tesseract --version 2>&1 | head -1)"
    fi

    # Create tesseract symlink for consistent lookup
    if [ ! -f "$NATIVE_DIR/tesseract" ]; then
      TESS_BIN=$(command -v tesseract 2>/dev/null || echo "")
      if [ -n "$TESS_BIN" ]; then
        ln -sf "$TESS_BIN" "$NATIVE_DIR/tesseract"
        echo "Symlinked $NATIVE_DIR/tesseract -> $TESS_BIN"
      fi
    fi
    ;;

  mac)
    echo "Checking system Tesseract/Leptonica..."
    if ! command -v tesseract &>/dev/null; then
      echo "Installing tesseract via Homebrew..."
      brew install tesseract leptonica
    else
      echo "Tesseract found: $(tesseract --version 2>&1 | head -1)"
    fi

    # Create tesseract symlink for consistent lookup
    if [ ! -f "$NATIVE_DIR/tesseract" ]; then
      TESS_BIN=$(command -v tesseract 2>/dev/null || echo "")
      if [ -n "$TESS_BIN" ]; then
        ln -sf "$TESS_BIN" "$NATIVE_DIR/tesseract"
        echo "Symlinked $NATIVE_DIR/tesseract -> $TESS_BIN"
      fi
    fi
    ;;
esac

# ── 4. Download jbig2enc ────────────────────────────────────────────────────
if [ "$FORCE_DOWNLOAD" = true ] || [ ! -x "$NATIVE_DIR/jbig2enc" ] && [ "$OS" != "win" ]; then
  echo "Downloading jbig2enc for $OS/$ARCH..."
  case "$OS" in
    linux)
      # Download precompiled binary for Linux
      JBIG2_URL="https://github.com/agl/jbig2enc/releases/download/0.32/jbig2enc-0.32-linux-x86_64"
      curl -fsSL -o "$NATIVE_DIR/jbig2enc" "$JBIG2_URL"
      chmod +x "$NATIVE_DIR/jbig2enc"
      echo "jbig2enc downloaded to $NATIVE_DIR"
      ;;
    mac)
      # Download precompiled binary for macOS
      JBIG2_URL="https://github.com/agl/jbig2enc/releases/download/0.32/jbig2enc-0.32-macos-x86_64"
      curl -fsSL -o "$NATIVE_DIR/jbig2enc" "$JBIG2_URL"
      chmod +x "$NATIVE_DIR/jbig2enc"
      echo "jbig2enc downloaded to $NATIVE_DIR"
      ;;
  esac
fi

# ── 5. Create run.sh convenience wrapper ────────────────────────────────────
if [ ! -f "$SCRIPT_DIR/run.sh" ]; then
  echo '#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
export TFOCR_JAVA_HOME="$SCRIPT_DIR/jdk/'"$OS"'"'"'"'"
exec "$TFOCR_JAVA_HOME/bin/java" -jar "$SCRIPT_DIR/build/libs/trulyfreeocr.jar" "$@"' > "$SCRIPT_DIR/run.sh"
  chmod +x "$SCRIPT_DIR/run.sh"
  echo "Created run.sh"
fi

echo ""
echo "── Bootstrap complete ──"
echo "Run:  ./run.sh input.pdf -o output.pdf"
echo "Build: $TFOCR_JAVA_HOME/bin/java -jar gradle/wrapper/gradle-wrapper.jar build"
