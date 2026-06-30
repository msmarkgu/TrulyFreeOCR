# TrulyFreeOCR

Commercially-safe OCR pipeline to produce fully searchable PDFs.

**Input**: Any PDF (searchable, non-searchable, or partially searchable)<br>
**Output**: Fully searchable, MRC-compressed PDF

---

## Who It's For

- **Commercial / Enterprise teams** — All dependencies are Apache 2.0 / BSD / MIT.
  No GPL, no AGPL, no proprietary components — no obligation to open source your code.

- **DevOps / Deployment engineers** — Every dependency (JDK, Tesseract, Leptonica,
  jbig2enc, language data) downloads into project subdirectories via `bootstrap.sh`.
  The entire stack is self-contained: copy the folder and it runs. No apt, brew,
  or system-wide installs needed on the target machine.

- **Document processing pipelines** — Headless CLI with JSONC settings file.
  All parameters (DPI, language, PSM, MRC on/off) are scriptable. The fat JAR
  has zero Java-classpath fuss — one file to copy, one command to run.

- **Teams scanning at scale** — MRC compression typically reduces PDF size 5–10×
  versus straight JPEG while keeping text razor-sharp via the JBIG2 foreground
  mask. Supports 20 languages including English, French, German, Spanish,
  Italian, Portuguese, Russian, Arabic, Hindi, Chinese, Japanese, Korean, and more.

- **Open-source contributors / integrators** — Pure-Java segmentation (no
  Leptonica native dependency). Clean pipeline API with PageExtractor →
  ImageSegmenter → OCREngine → PDFAssembler. Each component is independently
  testable and replaceable.

---

## Why TrulyFreeOCR

A survey of 19 open-source OCR projects (see [`docs/similar-projects-comparison.md`](docs/similar-projects-comparison.md)) found none that combine all five requirements for production document processing:

- **Business-friendly license** — Apache 2.0 (no disclosure obligations)
- **Self-contained** — single fat JAR + `bootstrap.sh`; no Python, no system deps
- **MRC compression** — JBIG2/CCITT foreground mask + JPEG/PNG background
- **Searchable PDF output** — invisible text layer + optional PDF/A-2b
- **No cloud / no GPU** — CPU-only, fully offline, zero data leaves the machine

Where other tools fall short:

- **VLM models** ([DeepSeek-OCR](https://github.com/deepseek-ai/DeepSeek-OCR), [GLM-OCR](https://github.com/zai-org/GLM-OCR), [Unlimited-OCR](https://github.com/baidu/Unlimited-OCR), etc.) produce the best OCR accuracy but require GPUs and only emit JSON/Markdown — no PDF output at all.
- **[OCRmyPDF](https://github.com/ocrmypdf/OCRmyPDF)** covers the full PDF pipeline but needs Ghostscript + Python + system deps; its MPL-2.0 license requires publishing modifications.
- **[Umi-OCR](https://github.com/hiroi-sora/Umi-OCR)** makes a nice desktop GUI but has no MRC compression, no PDF/A, and is Windows-focused.
- **[LlamaParse](https://github.com/run-llama/llama_index)** (LlamaIndex) is a cloud API with per-page costs, no offline mode, and no MRC/PDF/A output.
- **[EasyOCR](https://github.com/JaidedAI/EasyOCR) / [docTR](https://github.com/mindee/doctr) / [PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR) / [RapidOCR](https://github.com/RapidAI/RapidOCR)** are OCR libraries, not PDF tools — they extract text but produce no searchable PDF.
- **[surya](https://github.com/VikParuchuri/surya) / [MonkeyOCR](https://github.com/Yuliang-Liu/MonkeyOCR)** have non-commercial model weight restrictions, making them unsuitable for commercial deployment.

TrulyFreeOCR fills the gap: deploy anywhere, no cloud, no GPU, no license worries — just a JAR and a PDF.

---

## Getting Started

```bash
git clone https://github.com/msmarkgu/TrulyFreeOCR.git
cd TrulyFreeOCR

# Install dependencies (JDK, Tesseract, tessdata, jbig2enc, native libs or executables)
./bootstrap.sh

# Build the fat JAR (contains all Java dependencies)
./gradlew build

# Run all tests (optional)
./gradlew test
```

### Basic Usage

```bash
# Minimal: process a PDF with default settings
java -jar build/libs/trulyfreeocr.jar input.pdf

# Specify output path
java -jar build/libs/trulyfreeocr.jar input.pdf -o output.pdf

# Use a different language (fra, spa, deu, chi_sim, chi_tra, jpn)
java -jar build/libs/trulyfreeocr.jar input.pdf --language fra

# Adjust Tesseract page segmentation mode
java -jar build/libs/trulyfreeocr.jar input.pdf --psm 6

# Lower DPI for faster processing (less accurate)
java -jar build/libs/trulyfreeocr.jar input.pdf --dpi 200

# Disable MRC compression (faster assembly, larger files)
java -jar build/libs/trulyfreeocr.jar input.pdf --no-mrc

# Enable PDF/A-2b output
java -jar build/libs/trulyfreeocr.jar input.pdf --pdfa

# Control segmentation threads (default: # of CPU cores). OCR uses min(threads, 4).
java -jar build/libs/trulyfreeocr.jar input.pdf --threads 2

# Custom native/tessdata directories
java -jar build/libs/trulyfreeocr.jar input.pdf \
    --native-dir ./custom-native --tessdata-dir ./custom-tessdata

# Load settings from a custom file (overrides settings.jsonc)
java -jar build/libs/trulyfreeocr.jar input.pdf --settings /path/to/custom.jsonc

# Using the project-local JDK wrapper
TFOCR_JAVA_HOME=jdk/linux ./gradlew run --args="input.pdf -o output.pdf"
```

CLI flags override `settings.jsonc` values, which override hardcoded defaults. Run with `--help` for all options.

---

## Pipeline

```
Input PDF
    |
    v
PageExtractor  — PDFBox PDFRenderer (configurable DPI) → per-page BufferedImage
    |
    +---------+----------+
    |         |          |
    v         v          v
ImageSegmenter  |   OCREngine          (parallel across pages, configurable threads)
    |           |      |
    |           | Tesseract CLI
    |           | word-level bboxes with TSV output
    +----+------+
         |
         v
PDFAssembler — Background layer + CCITT G4 foreground mask stencil
    |            + invisible OCR text (rendering mode 3)
    v
Output PDF (searchable, compressed; optionally PDF/A-2b)
```

---

## Mixed Raster Content (MRC) Compression

TrulyFreeOCR uses an MRC-like approach (ISO 32000-2 / TIFF-FX) to decompose each page into three layers, each compressed with a codec suited to its content:

| Layer | Content | Codec | Rationale |
|---|---|---|---|
| **Background** | Cleaned page image (de-speckled, de-noised) | **JPEG** (quality 0.85) | Photographic content compresses well with lossy JPEG; by removing text noise first, quality remains high at small file sizes |
| **Foreground mask** | Binary text pixels from Otsu binarization | **CCITT G4** (fax) or **JBIG2** (when jbig2enc is available) | Binary text is ideal for bi-level compression; JBIG2 gives 2–5× smaller than G4 by exploiting symbol repetition |
| **Text layer** | OCR words at original positions | **Invisible PDF text** (rendering mode 3) | Vector text is negligible in size, makes the PDF fully searchable and selectable, and requires no embedding (Standard 14 fonts) |

When rendered, layers are stacked: background → foreground stencil (punches through to reveal sharp text pixels) → invisible text (for selection/search). The result is a PDF that looks like the original scan but is fully searchable and dramatically smaller.

### With vs Without MRC

| Content type | Without MRC (JPEG only) | With MRC (background JPEG + foreground mask + text) | Why |
|---|---|---|---|
| Text-heavy page (letter, report) | Large — JPEG must preserve every sharp edge, wasting bits on text contrast | Small — text is extracted into a compact binary mask; background becomes smooth (easy to compress) | Mask compresses text at 0.01–0.05 bits/pixel vs 0.5–1.0 bits/pixel in JPEG |
| Mixed page (text + photo) | Baseline | Similar savings — photo stays in JPEG, text pulled into mask | Photo unaffected; text layer shrinks |
| Full-page photo | Baseline | Slightly larger (mask adds overhead with no text to compress) | MRC detects no foreground text; overhead is negligible |

For a typical text document, MRC output is **5–10× smaller** than JPEG-only while keeping text edges perfectly sharp.

Disable MRC with `--no-mrc` to skip segmentation and output raw JPEG backgrounds with text layer only (faster assembly, larger files).

---

## Configuration

All pipeline parameters are configurable via `settings.jsonc` in the project root directory (JSON with `//` and `/* */` comments). CLI flags override file settings, which override hardcoded defaults.

| Setting | Default | Description |
|---|---|---|
| `native.dir` | `./native` | Native binaries directory (jbig2enc, etc.) |
| `tessdata.dir` | `./tessdata` | Tesseract language data directory |
| `tesseract.path` | `./native/linux/tesseract` | Tesseract executable (project-local wrapper) |
| `tesseract.language` | `eng` | Tesseract language model |
| `tesseract.psm` | `1` | Page segmentation mode |
| `pipeline.mrc.enabled` | `true` | Enable MRC compression (background + foreground mask + text) |
| `pipeline.ocr.maxThreads` | `1` | Max concurrent Tesseract subprocesses (1 = sequential OCR; increase on fast NVMe + >16 GB RAM) |
| `rendering.dpi` | `300` | PDF rendering resolution |
| `segmenter.tileSize` | `64` | Background normalization tile size (px) |
| `segmenter.percentile` | `0.95` | Background level percentile per tile |
| `segmenter.inpaintRadius` | `3` | Inpainting search radius (px) |
| `pdf.font` | `HELVETICA` | Standard 14 font for OCR text |
| `pdf.minFontSize` | `1` | Minimum OCR text font size (pt) |
| `pdf.pdfa.enabled` | `false` | Enable PDF/A-2b output (adds XMP metadata, sRGB OutputIntent) |
| `pdf.pdfa.fontPath` | `""` | Path to a TrueType font for embedding in PDF/A; empty = Standard 14 (non-embedded) |
| `output.file` | `output.pdf` | Default output file path |

CLI flags that override corresponding settings: `--dpi`, `--language`, `--psm`, `--no-mrc`, `--pdfa`, `--threads`, `--native-dir`, `--tessdata-dir`, `-o`/`--output`, `--settings` (path to a custom settings file).

---

## OCR Quality

| Metric | Regular prose pages | Notes |
|---|---|---|
| **Word Error Rate** | 0.9% – 3.2% | Well within the 5% target for standard documents |
| **Character Error Rate** | 0.2% – 1.2% | Sub-character accuracy on clean scans |
| **Word Recall** | 98.7% | Almost all ground-truth words detected |
| **Word Precision** | 98.7% | Very few false positives |
| **Mean Confidence** | 89.5 – 94.7 | Tesseract per-word confidence score |

Results measured on a 10-page public-domain prose corpus at 300 DPI, English language.
Non-standard layouts (title pages, multi-language extracts) show higher WER (~60–70%)
but are not representative of typical document content. See [`Evaluation.md`](Evaluation.md)
for the full methodology, per-page breakdown, and parameter sensitivity plans.

---

## Testing

Tests use generated sample PDFs in `tests/test-files/`:
- `blank.pdf` — single blank US Letter page
- `simple-text.pdf` — one page with lorem ipsum text
- `multi-page.pdf` — three-page document with distinct content per page
- `two-column.pdf` — two-column layout
- `with-annotations.pdf` — bookmarks, links, and comments
- `noisy-scan.pdf` — simulated scanner noise and skew

Regenerate test PDFs with:
```bash
./gradlew generateTestPdfs
```

See [`Evaluation.md`](Evaluation.md) for the OCR accuracy evaluation plan —
WER/CER targets, parameter sensitivity sweeps, and performance baselines.

---

## Components

| Component | What it does |
|---|---|
| **PageExtractor** | Renders each PDF page to a `BufferedImage` at configurable DPI using PDFBox's `PDFRenderer.renderImageWithDPI()` |
| **ImageSegmenter** | Pure-Java page segmentation: grayscale conversion → background normalization → Otsu binarization → inpainting. No Leptonica dependency. |
| **OCREngine** | Delegates to Tesseract CLI (not JNA/Tess4J) via subprocess with TSV output. Avoids native-library version mismatches. |
| **JBIG2Compressor** | Compresses binary foreground masks via jbig2enc (`-p -s`). Falls back to CCITT Group 4 fax encoding via PDFBox when jbig2enc is unavailable. |
| **PDFAssembler** | Re-assembles the output PDF: background (JPEG or lossless PNG) + CCITT G4 foreground stencil + invisible OCR text (rendering mode 3, Standard 14 fonts). |
| **MetadataPreserver** | Copies document info, outlines (bookmarks), per-page annotations, and XMP metadata from source to output. |

---

## Dependencies

### Build & Runtime (from Gradle)

| Dependency | Version | License | Project |
|---|---|---|---|
| PDFBox | 3.0.6 | Apache 2.0 | https://pdfbox.apache.org |
| picocli | 4.7.6 | Apache 2.0 | https://picocli.info |

### Build-time Only

| Dependency | Version | License | Project |
|---|---|---|---|
| Shadow plugin | 8.1.1 | Apache 2.0 | https://github.com/johnrengelman/shadow |
| Gradle wrapper | 8.0.1 | Apache 2.0 | https://gradle.org |

### Test (from Gradle)

These dependencies are test-only and never ship in the production fat JAR.

| Dependency | Version | License | Project |
|---|---|---|---|
| JUnit Jupiter | 5.11.4 | EPL 2.0 | https://junit.org/junit5/ |
| JUnit Platform Launcher | 1.11.4 | EPL 2.0 | https://junit.org/junit5/ |

EPL 2.0 (Eclipse Public License) is a weak copyleft license that allows
commercial use — the copyleft only covers the EPL library itself, not the
code that uses it. Since these are test-only dependencies, they are not
distributed with the application.

### Native / Bundled

All native dependencies are installed in project subdirectories and gitignored.
The `bootstrap.sh` script handles setting them up from scratch.

| Dependency | Project Location | License | Project |
|---|---|---|---|
| OpenJDK 21 LTS | `jdk/` | GPL 2.0 + Classpath Exception | https://adoptium.net |
| Tesseract OCR | `native/$OS/tesseract` → `tesseract.bin` + `native/$OS/lib/libtesseract.so.5` | Apache 2.0 | https://github.com/tesseract-ocr/tesseract |
| Leptonica | `native/$OS/lib/liblept.so.5` | BSD 2-Clause | https://github.com/DanBloomberg/leptonica |
| jbig2enc | `native/$OS/jbig2enc` → `jbig2enc.bin` + `native/$OS/lib/libjbig2enc.so.0` | Apache 2.0 | https://github.com/agl/jbig2enc |
| Tesseract language data | `tessdata/*.traineddata` (eng, fra, spa, deu, chi_sim, chi_tra, jpn) | Apache 2.0 | https://github.com/tesseract-ocr/tessdata |

Binary wrappers in `native/$OS/` set `LD_LIBRARY_PATH` to `native/$OS/lib/` so
the project-local shared libraries are used instead of system-wide ones.

---

<details>
<summary>Current Status</summary>

| Phase | Component | Status |
|-------|-----------|--------|
| 1 | Gradle scaffold + CLI | Done |
| 2 | PageExtractor | Done |
| 3 | OCREngine | Done |
| 4 | ImageSegmenter | Done |
| 5 | PDFAssembler (basic) | Done |
| 6 | JBIG2Compressor + SubprocessRunner | Done |
| 7 | PDFAssembler JBIG2 integration | Done |
| 8 | MetadataPreserver | Done |
| 9 | bootstrap scripts | Done |
| 10 | Integration tests | Done |
| 11 | Concurrent page processing | Done |
| 12 | PDF/A-2b output | Done |
| 13 | 20 language bundles | Done |

</details>

---

## License

TrulyFreeOCR itself is made available under the Apache 2.0 License. All dependencies listed above use permissive open-source licenses compatible with commercial use. A full `NOTICE.md` with copyright attributions is included in the repository.
