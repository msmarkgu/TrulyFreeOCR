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

# Derive Debian architecture and multiarch triplet for dynamic package URLs
DEB_ARCH=$(dpkg --print-architecture 2>/dev/null || echo "amd64")
case "$DEB_ARCH" in
  amd64) LIB_ARCH="x86_64-linux-gnu" ;;
  arm64) LIB_ARCH="aarch64-linux-gnu" ;;
  *)     echo "Warning: unknown Debian arch '$DEB_ARCH', assuming amd64"
         DEB_ARCH="amd64"; LIB_ARCH="x86_64-linux-gnu" ;;
esac

# ── Flag parsing ────────────────────────────────────────────────────────────
FORCE_DOWNLOAD=false
PADDLE=false
for arg in "$@"; do
  case "$arg" in
    --force)         FORCE_DOWNLOAD=true  ;;
    --paddle) PADDLE=true   ;;
  esac
done
if [ "$FORCE_DOWNLOAD" = true ]; then
  echo "Forcing re-download of all dependencies..."
fi

# ── 1. Download OpenJDK 21 LTS ──────────────────────────────────────────────

JDK_DIR="$SCRIPT_DIR/deps/jdk"
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

# Find actual JDK home (flat extraction with --strip-components=1)
if [ "$OS" = "mac" ]; then
  JAVA_HOME_PATH=$(ls -d "$JDK_DIR"/jdk-*/Contents/Home 2>/dev/null | head -1 || true)
else
  JAVA_HOME_PATH=$(ls -d "$JDK_DIR"/jdk-* 2>/dev/null | head -1 || true)
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

# Set TFOCR_JAVA_HOME for subsequent steps
export TFOCR_JAVA_HOME="$JAVA_HOME_PATH"

# ── 2. Download Gradle 8.0.1 ─────────────────────────────────────────────────
GRADLE_DIR="$SCRIPT_DIR/deps/gradle"
GRADLE_VERSION="8.0.1"
if [ "$FORCE_DOWNLOAD" = true ] || [ ! -x "$GRADLE_DIR/bin/gradle" ]; then
  echo "Downloading Gradle $GRADLE_VERSION..."
  rm -rf "$GRADLE_DIR"
  mkdir -p "$GRADLE_DIR"
  curl -fsSL -o "$SCRIPT_DIR/gradle-bin.zip" \
    "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
  unzip -qo "$SCRIPT_DIR/gradle-bin.zip" -d "$SCRIPT_DIR/deps"
  # Move contents from versioned folder to deps/gradle
  if [ -d "$SCRIPT_DIR/deps/gradle-${GRADLE_VERSION}" ]; then
    mv "$SCRIPT_DIR/deps/gradle-${GRADLE_VERSION}"/* "$GRADLE_DIR/"
    rmdir "$SCRIPT_DIR/deps/gradle-${GRADLE_VERSION}"
  fi
  rm -f "$SCRIPT_DIR/gradle-bin.zip"
  chmod +x "$GRADLE_DIR/bin/gradle"
  echo "Gradle $GRADLE_VERSION downloaded to $GRADLE_DIR"
else
  echo "Gradle already present in $GRADLE_DIR"
fi

# ── 4. Download Tesseract language data ─────────────────────────────────────
TESSDATA_DIR="$SCRIPT_DIR/deps/tesseract/tessdata"
mkdir -p "$TESSDATA_DIR"

LANGUAGES="eng fra deu spa chi_sim chi_tra jpn osd"
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

# Create Tesseract TSV config file (required for OCR output)
mkdir -p "$TESSDATA_DIR/configs"
if [ ! -f "$TESSDATA_DIR/configs/tsv" ]; then
  echo "tessedit_create_tsv 1" > "$TESSDATA_DIR/configs/tsv"
  echo "Created tessdata/configs/tsv"
fi

# ── 5. Download Tesseract + jbig2enc project-local binaries ────────────
TESSERACT_DIR="$SCRIPT_DIR/deps/tesseract/$OS"
JBIG2ENC_DIR="$SCRIPT_DIR/deps/jbig2enc/$OS"
mkdir -p "$TESSERACT_DIR/lib" "$JBIG2ENC_DIR/lib"

if [ "$FORCE_DOWNLOAD" = true ] || [ ! -x "$TESSERACT_DIR/tesseract.bin" ]; then
  echo "Downloading Tesseract and Leptonica for $OS/$ARCH..."
  TMPDIR=$(mktemp -d)
  case "$OS" in
    linux)
      if command -v apt-get &>/dev/null; then
        cd "$TMPDIR"
        apt-get download tesseract-ocr libtesseract5 liblept5 jbig2 libjbig2enc0t64 2>/dev/null || {
          curl -fsSL -o libtesseract5.deb \
            "http://archive.ubuntu.com/ubuntu/pool/main/t/tesseract/libtesseract5_5.2.0-1build3_${DEB_ARCH}.deb"
          curl -fsSL -o tesseract-ocr.deb \
            "http://archive.ubuntu.com/ubuntu/pool/main/t/tesseract/tesseract-ocr_5.2.0-1build3_${DEB_ARCH}.deb"
          curl -fsSL -o liblept5.deb \
            "http://archive.ubuntu.com/ubuntu/pool/main/l/leptonlib/liblept5_1.82.0-3build3_${DEB_ARCH}.deb"
          curl -fsSL -o jbig2.deb \
            "http://archive.ubuntu.com/ubuntu/pool/universe/j/jbig2enc/jbig2_0.29-2.1build1_${DEB_ARCH}.deb"
          curl -fsSL -o libjbig2enc0t64.deb \
            "http://archive.ubuntu.com/ubuntu/pool/universe/j/jbig2enc/libjbig2enc0t64_0.29-2.1build1_${DEB_ARCH}.deb"
        }
        for deb in "$TMPDIR"/*.deb; do [ -f "$deb" ] && dpkg-deb -x "$deb" "$TMPDIR/extract" 2>/dev/null; done
        # Tesseract binary + shared libs
        if [ -f "$TMPDIR/extract/usr/bin/tesseract" ]; then
          cp "$TMPDIR/extract/usr/bin/tesseract" "$TESSERACT_DIR/tesseract.bin"
          cp -a "$TMPDIR/extract/usr/lib/"*.so* "$TESSERACT_DIR/lib/" 2>/dev/null || true
          cp -a "$TMPDIR/extract/usr/lib/${LIB_ARCH}/"*.so* "$TESSERACT_DIR/lib/" 2>/dev/null || true
          chmod +x "$TESSERACT_DIR/tesseract.bin"
          echo "Tesseract installed to $TESSERACT_DIR"
        else
          echo "WARNING: Could not extract Tesseract."
        fi
        # jbig2enc CLI tool + shared lib
        if [ -f "$TMPDIR/extract/usr/bin/jbig2" ]; then
          cp "$TMPDIR/extract/usr/bin/jbig2" "$JBIG2ENC_DIR/jbig2.bin"
          cp -a "$TMPDIR/extract/usr/lib/"*.so* "$JBIG2ENC_DIR/lib/" 2>/dev/null || true
          cp -a "$TMPDIR/extract/usr/lib/${LIB_ARCH}/"*.so* "$JBIG2ENC_DIR/lib/" 2>/dev/null || true
          chmod +x "$JBIG2ENC_DIR/jbig2.bin"
          echo "jbig2enc installed to $JBIG2ENC_DIR"
        fi
        # liblept is needed by both — copy to jbig2enc
        cp -a "$TESSERACT_DIR/lib/liblept"* "$JBIG2ENC_DIR/lib/" 2>/dev/null || true
      fi
      ;;
    mac)
      if command -v brew &>/dev/null; then
        echo "Fetching Tesseract via Homebrew..."
        brew fetch --force --bottle tesseract
        brew fetch --force --bottle leptonica
        HOMEBREW_CACHE=$(brew --cache 2>/dev/null || echo "$HOME/Library/Caches/Homebrew")
        for tap in "$HOMEBREW_CACHE"/downloads/*/tesseract--*.tar.gz \
                   "$HOMEBREW_CACHE"/downloads/*/leptonica--*.tar.gz; do
          [ -f "$tap" ] && tar -xzf "$tap" -C "$TMPDIR/extract" 2>/dev/null || true
        done
        if [ -f "$TMPDIR/extract/usr/local/bin/tesseract" ]; then
          cp "$TMPDIR/extract/usr/local/bin/tesseract" "$TESSERACT_DIR/tesseract.bin"
          cp -a "$TMPDIR/extract/usr/local/lib/"*.dylib "$TESSERACT_DIR/lib/" 2>/dev/null || true
          chmod +x "$TESSERACT_DIR/tesseract.bin"
          echo "Tesseract installed to $TESSERACT_DIR"
        else
          echo "WARNING: Could not extract Tesseract from Homebrew cache."
        fi
      fi
      ;;
  esac
  rm -rf "$TMPDIR"
fi

# Create the tesseract wrapper script
if [ ! -f "$TESSERACT_DIR/tesseract" ]; then
  cat > "$TESSERACT_DIR/tesseract" <<WRAPPER
#!/bin/bash
SCRIPT_DIR="\$(cd "\$(dirname "\$0")" && pwd)"
PARENT_DIR="\$(cd "\$SCRIPT_DIR/.." && pwd)"
export TESSDATA_PREFIX="\${TESSDATA_PREFIX:-\$PARENT_DIR/tessdata}"
export LD_LIBRARY_PATH="\$SCRIPT_DIR/lib\${LD_LIBRARY_PATH:+:\$LD_LIBRARY_PATH}"
exec "\$SCRIPT_DIR/tesseract.bin" "\$@"
WRAPPER
  chmod +x "$TESSERACT_DIR/tesseract"
  echo "Created Tesseract wrapper at $TESSERACT_DIR/tesseract"
fi

# Create the jbig2enc wrapper (Java looks for 'jbig2enc', binary is 'jbig2.bin')
if [ ! -f "$JBIG2ENC_DIR/jbig2enc" ] && [ -x "$JBIG2ENC_DIR/jbig2.bin" ]; then
  cat > "$JBIG2ENC_DIR/jbig2enc" <<WRAPPER
#!/bin/bash
SCRIPT_DIR="\$(cd "\$(dirname "\$0")" && pwd)"
export LD_LIBRARY_PATH="\$SCRIPT_DIR/lib\${LD_LIBRARY_PATH:+:\$LD_LIBRARY_PATH}"
exec "\$SCRIPT_DIR/jbig2.bin" "\$@"
WRAPPER
  chmod +x "$JBIG2ENC_DIR/jbig2enc"
  echo "Created jbig2enc wrapper at $JBIG2ENC_DIR/jbig2enc"
fi

# ── 6. Download PP-OCRv6 ONNX models for PaddleOCR engine (optional) ──────
if [ "$PADDLE" = true ]; then
  PADDLEOCR_DIR="$SCRIPT_DIR/deps/paddleocr"
  mkdir -p "$PADDLEOCR_DIR"

  if [ "$FORCE_DOWNLOAD" = true ] || [ ! -f "$PADDLEOCR_DIR/det.onnx" ]; then
    echo "Downloading PP-OCRv6_small_det ONNX model..."
    curl -fsSL -o "$PADDLEOCR_DIR/det.onnx" \
      "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx/resolve/main/inference.onnx"
    echo "Detection model downloaded ($(du -h "$PADDLEOCR_DIR/det.onnx" | cut -f1))"
  else
    echo "PP-OCRv6 detection model already present"
  fi

  if [ "$FORCE_DOWNLOAD" = true ] || [ ! -f "$PADDLEOCR_DIR/rec.onnx" ]; then
    echo "Downloading PP-OCRv6_small_rec ONNX model..."
    curl -fsSL -o "$PADDLEOCR_DIR/rec.onnx" \
      "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx/resolve/main/inference.onnx"
    echo "Recognition model downloaded ($(du -h "$PADDLEOCR_DIR/rec.onnx" | cut -f1))"
  else
    echo "PP-OCRv6 recognition model already present"
  fi

  # Download and extract character dictionary from model's inference.yml
  if [ "$FORCE_DOWNLOAD" = true ] || [ ! -f "$PADDLEOCR_DIR/ppocr_keys_v6.txt" ]; then
    echo "Extracting character dictionary from model inference.yml..."
    # Write extraction script to temp file to avoid quoting issues
    PYEXTRACT=$(mktemp)
    cat > "$PYEXTRACT" << 'PYEOF'
import sys
in_dict = False
for line in sys.stdin:
    if 'character_dict:' in line:
        in_dict = True
        continue
    if in_dict:
        s = line.strip()
        if not s.startswith('- '):
            break
        val = s[2:]
        if val.startswith("'") and val.endswith("'"):
            inner = val[1:-1].replace("''", "'")
            print(inner)
PYEOF
    curl -fsSL "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx/resolve/main/inference.yml" \
      | python3 "$PYEXTRACT" > "$PADDLEOCR_DIR/ppocr_keys_v6.txt"
    rm -f "$PYEXTRACT"
    echo "Character dictionary downloaded ($(wc -l < "$PADDLEOCR_DIR/ppocr_keys_v6.txt") chars)"
  else
    echo "Character dictionary already present"
  fi
fi

# ── 7. Download jbig2enc (macOS only) ──────────────────────────────────────────
# Linux: handled via apt-get download above. macOS falls back to Homebrew.
case "$OS" in
  mac)
    if ! command -v jbig2 &>/dev/null && [ ! -x "$NATIVE_DIR/jbig2" ]; then
      echo "jbig2enc not found. Install with: brew install jbig2enc"
    fi
    ;;
esac

# ── 8. Build project ────────────────────────────────────────────────────────
echo ""
echo "── Bootstrap complete ──"
echo "Run:  ./run.sh input.pdf -o output.pdf"
echo "Build: ./gradlew build"
echo ""
echo "Flags: --force         force re-download of all dependencies"
echo "       --paddle   also download PP-OCRv6 ONNX models (~30 MB) for --ocr-engine paddle"
