#!/usr/bin/env bash
# Evaluate OCR quality by comparing pipeline text output against original
# PDF text. Uses EvalOcrRunner.java for CER/WER computation.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PDF_DIR="$REPO_DIR/tests/test-files/real-world"
JAR="$REPO_DIR/build/libs/trulyfreeocr.jar"
JAVA="$REPO_DIR/jdk/bin/java"
if [ ! -x "$JAVA" ]; then
  echo "ERROR: No JDK 21 found at $JAVA"
  exit 1
fi
REPORT_DIR="$REPO_DIR/eval"
RUNNER_CLASS="EvalOcrRunner"

if [ ! -f "$JAR" ]; then
  echo "Building fat JAR ..."
  "$REPO_DIR"/gradlew build -q
fi

RUNNER_SRC="$SCRIPT_DIR/$RUNNER_CLASS.java"
RUNNER_CLASSFILE="$SCRIPT_DIR/$RUNNER_CLASS.class"
JAVAC="$REPO_DIR/jdk/bin/javac"
if [ ! -f "$RUNNER_CLASSFILE" ] || [ "$RUNNER_SRC" -nt "$RUNNER_CLASSFILE" ]; then
  echo "Compiling EvalOcrRunner ..."
  "$JAVAC" -d "$SCRIPT_DIR" "$RUNNER_SRC"
fi

RUN_TS=$(date '+%Y-%m-%d %H:%M')
FILTER="${1:-}"

mkdir -p "$REPORT_DIR"
REPORT="$REPORT_DIR/report-ocr.md"

if [ ! -f "$REPORT" ] || [ ! -s "$REPORT" ]; then
  cat > "$REPORT" <<'EOF'
# OCR Quality Evaluation
EOF
  echo "" >> "$REPORT"
  printf "| %-35s | %-16s | %5s | %8s | %8s | %8s | %8s | %6s | %6s |\n" \
    "File" "Date" "Pages" "GT chars" "OCR chars" "GT words" "OCR words" "CER" "WER" >> "$REPORT"
  printf "|%s|%s|%s|%s|%s|%s|%s|%s|%s|\n" \
    "-----------------------------------" \
    "------------------" \
    "-------" \
    "----------" \
    "----------" \
    "----------" \
    "----------" \
    "--------" \
    "--------" >> "$REPORT"
fi

TMPDIR="$REPO_DIR/temp/benchmark-eval"
mkdir -p "$TMPDIR"

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
  gt_txt="$TMPDIR/${base}-gt.txt"
  ocr_txt="$TMPDIR/${base}-ocr.txt"
  out_pdf="$TMPDIR/${base}-out.pdf"

  pdftotext "$pdf" "$gt_txt" 2>/dev/null

  "$JAVA" -jar "$JAR" "$pdf" -o "$out_pdf" --txt-output "$ocr_txt"

  eval_result=$("$JAVA" -cp "$SCRIPT_DIR" "$RUNNER_CLASS" "$gt_txt" "$ocr_txt" 2>/dev/null)

  gt_chars=$(echo "$eval_result" | grep '^gt_chars' | cut -f2)
  ocr_chars=$(echo "$eval_result" | grep '^ocr_chars' | cut -f2)
  gt_words=$(echo "$eval_result" | grep '^gt_words' | cut -f2)
  ocr_words=$(echo "$eval_result" | grep '^ocr_words' | cut -f2)
  cer=$(echo "$eval_result" | grep '^cer' | cut -f2)
  wer=$(echo "$eval_result" | grep '^wer' | cut -f2)

  cer_pct=$(echo "scale=2; $cer * 100" | bc 2>/dev/null || echo "0")
  wer_pct=$(echo "scale=2; $wer * 100" | bc 2>/dev/null || echo "0")

  printf "| %-35s | %-16s | %5s | %8d | %8d | %8d | %8d | %5.2f%% | %5.2f%% |\n" \
    "$name" "$RUN_TS" "$pages" \
    "$gt_chars" "$ocr_chars" "$gt_words" "$ocr_words" \
    "$cer_pct" "$wer_pct" >> "$REPORT"
done

echo "OCR evaluation complete."
cat "$REPORT"
