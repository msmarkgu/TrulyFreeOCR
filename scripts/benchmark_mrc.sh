#!/usr/bin/env bash
# Benchmark MRC compression effect: compare output file size and time
# with and without MRC across all real-world test PDFs.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PDF_DIR="$REPO_DIR/tests/test-files/real-world"
JAR="$REPO_DIR/build/libs/trulyfreeocr.jar"
JAVA="$REPO_DIR/jdk/bin/java"
if [ ! -x "$JAVA" ]; then
  # Try versioned subdirectory
  JAVA=$(ls "$REPO_DIR"/jdk/linux/jdk-*/bin/java 2>/dev/null | head -1)
fi
if [ ! -x "$JAVA" ]; then
  # Try PATH
  JAVA=$(command -v java 2>/dev/null || echo "")
fi
if [ ! -x "$JAVA" ]; then
  echo "ERROR: No Java 21 found. Run bootstrap.sh or set PATH."
  exit 1
fi
REPORT_DIR="$REPO_DIR/eval"

if [ ! -f "$JAR" ]; then
  echo "Building fat JAR ..."
  "$REPO_DIR"/gradlew build -q
fi

RUN_TS=$(date '+%Y-%m-%d %H:%M')
FILTER="${1:-}"

mkdir -p "$REPORT_DIR"
REPORT="$REPORT_DIR/report-mrc.md"

TMPDIR="$REPO_DIR/temp/benchmark-mrc"
mkdir -p "$TMPDIR"

if [ ! -f "$REPORT" ] || [ ! -s "$REPORT" ]; then
  cat > "$REPORT" <<'EOF'
# MRC Compression Benchmark
EOF
  echo "" >> "$REPORT"
  printf "| %-35s | %-16s | %5s | %10s | %10s | %10s | %10s | %10s |\n" \
    "File" "Date" "Pages" "MRC size" "No-MRC size" "Ratio" "MRC time" "No-MRC time" >> "$REPORT"
  printf "|%s|%s|%s|%s|%s|%s|%s|%s|\n" \
    "-----------------------------------" \
    "------------------" \
    "-------" \
    "----------" \
    "----------" \
    "----------" \
    "----------" \
    "----------" >> "$REPORT"
fi

if [ -n "$FILTER" ] && [[ "$FILTER" == */* ]]; then
  PDFS=("$FILTER")
else
  PDFS=("$PDF_DIR"/${FILTER:-*.pdf})
fi

for pdf in "${PDFS[@]}"; do
  if [ ! -f "$pdf" ]; then
    echo "  File not found: $pdf"
    continue
  fi
  name=$(basename "$pdf")
  pages=$(pdfinfo "$pdf" 2>/dev/null | grep 'Pages:' | awk '{print $2}')
  pages=${pages:-?}

  echo "  Processing $name ($pages pages) ..."

  base="${name%.pdf}"
  tmp_mrc="$TMPDIR/mrc-${base}.pdf"
  tmp_nomrc="$TMPDIR/nomrc-${base}.pdf"

  start=$(date +%s.%N)
  "$JAVA" -jar "$JAR" "$pdf" -o "$tmp_mrc"
  end=$(date +%s.%N)
  mrc_time=$(echo "$end - $start" | bc 2>/dev/null || echo "0")
  mrc_size=$(stat --format=%s "$tmp_mrc" 2>/dev/null || echo "0")

  start=$(date +%s.%N)
  "$JAVA" -jar "$JAR" --no-mrc "$pdf" -o "$tmp_nomrc"
  end=$(date +%s.%N)
  nomrc_time=$(echo "$end - $start" | bc 2>/dev/null || echo "0")
  nomrc_size=$(stat --format=%s "$tmp_nomrc" 2>/dev/null || echo "0")

  if [ "$mrc_size" -gt 0 ] 2>/dev/null && [ "$nomrc_size" -gt 0 ] 2>/dev/null; then
    ratio=$(echo "scale=2; $nomrc_size / $mrc_size" | bc 2>/dev/null || echo "0")
  else
    ratio="0"
  fi

  printf "| %-35s | %-16s | %5s | %8d KB | %8d KB | %8.2f× | %8.1fs | %8.1fs |\n" \
    "$name" "$RUN_TS" "$pages" \
    "$(( mrc_size / 1024 ))" \
    "$(( nomrc_size / 1024 ))" \
    "$ratio" \
    "$mrc_time" \
    "$nomrc_time" >> "$REPORT"

done

echo "MRC benchmark complete."
cat "$REPORT"
