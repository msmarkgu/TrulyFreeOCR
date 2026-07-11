#!/usr/bin/env bash
# Benchmark multithreading speedup across all real-world test PDFs.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PDF_DIR="$REPO_DIR/tests/test-files/real-world"
JAR="$REPO_DIR/build/trulyfreeocr.jar"
JAVA="$REPO_DIR/deps/jdk/bin/java"
if [ ! -x "$JAVA" ]; then
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
REPORT="$REPORT_DIR/report-threads.md"

THREAD_COUNTS=(1 2 4 8)

TMPDIR="$REPO_DIR/temp/benchmark-threads"
mkdir -p "$TMPDIR"

if [ ! -f "$REPORT" ] || [ ! -s "$REPORT" ]; then
  cat > "$REPORT" <<'EOF'
# Multithreading Benchmark
EOF
  echo "" >> "$REPORT"
  printf "| %-35s | %-16s | %5s |" "File" "Date" "Pages" >> "$REPORT"
  for t in "${THREAD_COUNTS[@]}"; do
    printf " %8s | %9s |" "T($t)" "Speedup" >> "$REPORT"
  done
  printf " %6s |\n" "Pages/s" >> "$REPORT"

  printf "|%s|%s|%s|" \
    "-----------------------------------" \
    "------------------" \
    "-------" >> "$REPORT"
  for _ in "${THREAD_COUNTS[@]}"; do
    printf "%s|%s|" "----------" "-----------" >> "$REPORT"
  done
  printf "%s|\n" "--------" >> "$REPORT"
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

  printf "| %-35s | %-16s | %5s |" "$name" "$RUN_TS" "$pages" >> "$REPORT"

  base_t=""
  results=()

  for t in "${THREAD_COUNTS[@]}"; do
    base="${name%.pdf}"
    tmp_out="$TMPDIR/out-t${t}-${base}.pdf"

    start=$(date +%s.%N)
    "$JAVA" -jar "$JAR" --threads "$t" "$pdf" -o "$tmp_out"
    end=$(date +%s.%N)
    elapsed=$(echo "$end - $start" | bc 2>/dev/null || echo "0")

    if [ "$t" = "1" ]; then
      base_t="$elapsed"
    fi

    if [ "$(echo "$elapsed > 0" | bc 2>/dev/null)" = "1" ] && \
       [ -n "$base_t" ] && \
       [ "$(echo "$base_t > 0" | bc 2>/dev/null)" = "1" ]; then
      speedup=$(echo "scale=2; $base_t / $elapsed" | bc 2>/dev/null || echo "0")
    else
      speedup="0"
    fi

    results+=("$elapsed" "$speedup")
  done

  for ((i=0; i<${#results[@]}; i+=2)); do
    elapsed="${results[$i]}"
    speedup="${results[$((i+1))]}"
    printf " %7.1fs | %8.2f× |" "$elapsed" "$speedup" >> "$REPORT"
  done

  if [ -n "$base_t" ] && [ "$(echo "$base_t > 0" | bc 2>/dev/null)" = "1" ] && [ "$pages" != "?" ]; then
    pps=$(echo "scale=1; $pages / $base_t" | bc 2>/dev/null || echo "0")
  else
    pps="0"
  fi
  printf " %5.1f |\n" "$pps" >> "$REPORT"
done

echo "Benchmark complete."
cat "$REPORT"
