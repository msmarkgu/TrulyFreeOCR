# TrulyFreeOCR

Business-friendly open source OCR tool to produce fully searchable and highly compressed PDFs.
All runtime dependencies use permissive licenses (Apache 2.0 / MIT / BSD).

**Input**: Any PDF or image (PNG/JPEG/TIFF/BMP/GIF)<br>
**Output**: Fully searchable, MRC-compressed PDF ([Mixed Raster Content](https://en.wikipedia.org/wiki/Mixed_raster_content))

---

## Who It's For

- **Commercial / Enterprise teams** — All dependencies are Apache 2.0 / BSD / MIT.
  No GPL, no AGPL, no proprietary components — no obligation to open source your code.

- **DevOps / Deployment engineers** — Every dependency (Gradle, JDK, Tesseract, Leptonica,
  jbig2enc, language data) downloads into project subdirectories via `bootstrap.sh`
  (Linux/macOS) or `bootstrap.bat` (Windows). Zero admin rights required: no sudo,
  no brew, no apt-get install, no Windows installer wizard.
  The entire stack is self-contained: copy the folder and it runs on any machine.

- **Document processing pipeline** — Headless CLI with JSONC settings file.
  All parameters (DPI, language, PSM, MRC on/off) are scriptable. The fat JAR
  has zero Java-classpath fuss — one file to copy, one command to run.

- **High-volume document scanning** — MRC compression reduces PDF size ~80–90%
  (5–10× smaller) versus JPEG-only while keeping text razor-sharp via the JBIG2
  foreground mask. Ships with 7 trained language models (English, French, German, Spanish,
  Chinese Simplified, Chinese Traditional, Japanese). Add more by downloading
  Tesseract traineddata files.

- **Open-source contributors / integrators** — Pure-Java segmentation (no
  Leptonica native dependency). Clean pipeline API with PageExtractor →
  ImageSegmenter → OCREngine → PDFAssembler. Each component has focused unit
  tests and a well-defined contract.

---

## Why TrulyFreeOCR

A survey of 25+ open-source OCR projects (see [`docs/opensource-ocr-tools.md`](docs/opensource-ocr-tools.md)) found none that combine all five requirements for production document processing:

- **Business-friendly license** — Apache 2.0 (no disclosure obligations)
- **Self-contained** — single fat JAR + `bootstrap.sh`/`bootstrap.bat`; Gradle + JDK + native binaries all project-local; no sudo, no Python, no system deps
- **MRC compression** — JBIG2/CCITT foreground mask + JPEG/PNG background (JBIG2 binary included in project)
- **Searchable PDF output** — invisible text layer + optional PDF/A-2b
- **No cloud / no GPU** — CPU-only, fully offline, zero data leaves the machine

Where other tools fall short:

- **VLM models** ([DeepSeek-OCR](https://github.com/deepseek-ai/DeepSeek-OCR), [GLM-OCR](https://github.com/zai-org/GLM-OCR), [Unlimited-OCR](https://github.com/baidu/Unlimited-OCR), [Chandra OCR](https://github.com/chandra-ai/chandra-ocr), etc.) produce the best OCR accuracy but require GPUs and only emit JSON/Markdown — no PDF output at all.
- **[OCRmyPDF](https://github.com/ocrmypdf/OCRmyPDF)** covers the full PDF pipeline but needs Ghostscript + Python + system deps; its MPL-2.0 license requires publishing modifications.
- **[MinerU](https://github.com/opendatalab/MinerU)** is the most popular document extraction tool (74k★) but outputs Markdown/JSON only, requires Python + model downloads, and its custom license restricts deployments exceeding 100M MAU / $20M revenue.
- **[Umi-OCR](https://github.com/hiroi-sora/Umi-OCR)** makes a nice desktop GUI but has no MRC compression, no PDF/A, and is Windows-focused.
- **[NAPS2](https://github.com/cyanfish/naps2)** is a desktop scanning app with OCR, but uses GPL 2.0, requires .NET runtime, and has no MRC compression, no PDF/A, no headless server mode.
- **[paperless-ngx](https://github.com/paperless-ngx/paperless-ngx)** is a full self-hosted DMS with ML classification (42k★), but GPL-3.0 requires full application source disclosure, needs Django + DB + Redis, and lacks MRC compression and PDF/A.
- **[LlamaParse](https://github.com/run-llama/llama_index)** (LlamaIndex) / **[Unstructured](https://github.com/Unstructured-IO/unstructured)** are cloud/ETL tools for LLM ingestion, with per-page costs (LlamaParse) or local Python deps, and produce JSON/Markdown — not searchable PDFs.
- **[EasyOCR](https://github.com/JaidedAI/EasyOCR) / [docTR](https://github.com/mindee/doctr) / [PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR) / [RapidOCR](https://github.com/RapidAI/RapidOCR) / [tesseract.js](https://github.com/naptha/tesseract.js)** are OCR libraries, not PDF tools — they extract text but produce no searchable PDF.
- **[surya](https://github.com/VikParuchuri/surya) / [Marker](https://github.com/VikParuchuri/marker) / [MonkeyOCR](https://github.com/Yuliang-Liu/MonkeyOCR)** have non-commercial model weight restrictions, making them unsuitable for commercial deployment.

TrulyFreeOCR fills the gap: no license worries, no data sent to the cloud, and no GPU required. Simply run the self-contained fat JAR, input a PDF or image, and get a highly compressed, fully searchable PDF out.

---

## Getting Started

### Installation

**No admin rights needed** — every dependency is downloaded into project
subdirectories and stays there. The bootstrap script works on Linux, macOS,
and Windows.

1. Clone the repository:
   ```bash
   git clone https://github.com/msmarkgu/TrulyFreeOCR.git
   cd TrulyFreeOCR
   ```

2. Run the bootstrap script for your platform:
   ```bash
   # Linux / macOS
   ./bootstrap.sh

   # Windows (PowerShell or Command Prompt)
   bootstrap.bat
   ```
   This will install into local project subdirectories:
   - OpenJDK 21 LTS → `deps/jdk/`
   - Gradle 8.0.1 → `deps/gradle/`
   - Tesseract OCR engine → `deps/tesseract/$OS/`
   - Tesseract language data (eng, fra, deu, spa, chi_sim, chi_tra, jpn, osd) → `deps/tesseract/tessdata/`
   - jbig2enc for JBIG2 compression → `deps/jbig2enc/$OS/`
   - All required shared libraries → `deps/tesseract/$OS/lib/` and `deps/jbig2enc/$OS/lib/`

<details>
<summary>Resulting <code>deps/</code> structure (Linux shown; macOS/Windows analogous)</summary>

```
deps/
├── jdk/
│   ├── bin/            # java, javac, jar, ...
│   ├── lib/            # rt, modules, security, ...
│   └── ...
├── gradle/
│   ├── bin/            # gradle (build tool)
│   ├── lib/            # Gradle runtime libraries
│   └── ...
├── tesseract/
│   ├── linux/
│   │   ├── tesseract       # wrapper script (sets TESSDATA_PREFIX, LD_LIBRARY_PATH)
│   │   ├── tesseract.bin   # Tesseract OCR engine
│   │   └── lib/
│   │       ├── libtesseract.so.5 -> libtesseract.so.5.0.3
│   │       ├── libtesseract.so.5.0.3
│   │       ├── liblept.so -> liblept.so.5
│   │       ├── liblept.so.5 -> liblept.so.5.0.4
│   │       └── liblept.so.5.0.4
│   └── tessdata/
│       ├── eng.traineddata
│       ├── fra.traineddata
│       ├── deu.traineddata
│       ├── spa.traineddata
│       ├── chi_sim.traineddata
│       ├── chi_tra.traineddata
│       ├── jpn.traineddata
│       ├── osd.traineddata
│       └── configs/
│           └── tsv
└── jbig2enc/
    └── linux/
        ├── jbig2enc        # wrapper script (sets LD_LIBRARY_PATH)
        ├── jbig2.bin       # jbig2enc CLI (from Ubuntu jbig2 package)
        └── lib/
            ├── libjbig2enc.so.0 -> libjbig2enc.so.0.0.28
            ├── libjbig2enc.so.0.0.28
            ├── liblept.so -> liblept.so.5
            ├── liblept.so.5 -> liblept.so.5.0.4
            └── liblept.so.5.0.4
```
Leptonica (`liblept`) is shared: the canonical copy lives under `tesseract/$OS/lib/`,
and a copy is also placed under `jbig2enc/$OS/lib/` for the jbig2enc wrapper's
`LD_LIBRARY_PATH` to find at runtime.
</details>

3. To force re-download all dependencies:
   ```bash
   ./bootstrap.sh --force       # Linux / macOS
   bootstrap.bat --force        # Windows
   ```

### Verification

After running the bootstrap script, verify all dependencies were installed:

```bash
# Check JDK
./deps/jdk/bin/java -version

# Check Tesseract wrapper
./deps/tesseract/*/tesseract --version

# Check jbig2enc (Linux/macOS only; Windows uses CCITT G4 fallback)
ls -l ./deps/jbig2enc/*/jbig2 2>/dev/null || echo "jbig2enc not available (CCITT G4 fallback active)"

# Check Gradle
./deps/gradle/bin/gradle --version

# Check language data
ls ./deps/tesseract/tessdata/*.traineddata
```

If these files and binaries exist, the installation is complete.

### Usage

Generate a sample test PDF, or use your own:
```bash
./gradlew generateTestPdfs
```

Build the fat JAR:
```bash
./gradlew build
```

Basic usage (recommended — auto-builds, uses local JDK):
```bash
./run.sh tests/simple-text.pdf -o output.pdf
```

Or use an image file:
```bash
./run.sh scan.png -o output.pdf
```

If you prefer to invoke the JAR directly with the project-local JDK:
```bash
./deps/jdk/bin/java -jar build/trulyfreeocr.jar tests/simple-text.pdf -o output.pdf
```

Common options:
```bash
# Set DPI (default: 300)
./deps/jdk/bin/java -jar build/trulyfreeocr.jar tests/simple-text.pdf --dpi 300

# Set language (default: eng)
./deps/jdk/bin/java -jar build/trulyfreeocr.jar tests/simple-text.pdf --language spa

# Disable MRC compression
./deps/jdk/bin/java -jar build/trulyfreeocr.jar tests/simple-text.pdf --no-mrc

# Use 2 concurrent OCR threads (default: 1)
./deps/jdk/bin/java -jar build/trulyfreeocr.jar tests/simple-text.pdf --threads 2

# Export recognized text to a file
./deps/jdk/bin/java -jar build/trulyfreeocr.jar tests/simple-text.pdf --txt-output output.txt

# Export word bounding boxes as JSON
./deps/jdk/bin/java -jar build/trulyfreeocr.jar tests/simple-text.pdf --bbox-output words.json
```

For more options, run:
```bash
./deps/jdk/bin/java -jar build/trulyfreeocr.jar --help
```

See [`docs/test-runs.md`](docs/test-runs.md) for example benchmark runs with
real command lines showing `--threads`, `--no-mrc`, and MRC-enabled output
with timing and file-size results.

CLI flags override `settings.jsonc` values, which override hardcoded defaults.

---

## Pipeline

```
Input PDF / Image
    |
    v
PageExtractor  — PDFBox PDFRenderer (PDF) / ImageIO (image) → per-page BufferedImage
    |
    v (main thread, sequential — page provider not thread-safe)
+----------------------------+
| Worker pool (N threads)    |
|  per-page: toGrayscale →   |
|  ImageSegmenter → OCREngine|   (parallel across pages)
+----------------------------+
    |
    v
PDFAssembler — Background layer + CCITT G4 / JBIG2 foreground mask stencil
    |            + invisible OCR text (rendering mode 3)
    |            + metadata / bookmarks / annotations (via MetadataPreserver)
    v
Output PDF (searchable, compressed; optionally PDF/A-2b)
```

---

## Mixed Raster Content (MRC) Compression

MRC is an international standard (ITU-T T.44 / ISO/IEC 16485) for compound image compression — see the [Wikipedia article](https://en.wikipedia.org/wiki/Mixed_raster_content) for background.

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

For a typical text document, MRC output is **80–90% smaller (5–10×)** than JPEG-only while keeping text edges perfectly sharp. The background is downsampled and aggressively compressed since text sharpness is preserved by the binary foreground mask layer.

Disable MRC with `--no-mrc` to skip segmentation and output raw JPEG backgrounds with text layer only (faster assembly, larger files).

---

## Configuration

All pipeline parameters are configurable via `settings.jsonc` in the project root directory (JSON with `//` and `/* */` comments). CLI flags override file settings, which override hardcoded defaults.

| Setting | Default | Description |
|---|---|---|
| `native.dir` | `./deps/jbig2enc` | Native binaries directory (jbig2enc, etc.) |
| `tessdata.dir` | `./deps/tesseract/tessdata` | Tesseract language data directory |
| `tesseract.path` | `./deps/tesseract/linux/tesseract` | Tesseract executable (project-local wrapper) |
| `tesseract.language` | `eng` | Tesseract language model |
| `tesseract.psm` | `1` | Page segmentation mode |
| `pipeline.mrc.enabled` | `true` | Enable MRC compression (background + foreground mask + text) |
| `pipeline.ocr.maxThreads` | `1` | Max concurrent Tesseract subprocesses (1 = sequential OCR; increase on fast NVMe + >16 GB RAM) |
| `rendering.dpi` | `300` | PDF rendering resolution; for images, DPI is read from embedded metadata |
| `segmenter.tileSize` | `64` | Background normalization tile size (px) |
| `segmenter.percentile` | `0.95` | Background level percentile per tile |
| `segmenter.inpaintRadius` | `3` | Inpainting search radius (px) |
| `pdf.font` | `HELVETICA` | Standard 14 font for OCR text |
| `pdf.minFontSize` | `1` | Minimum OCR text font size (pt) |
| `pdf.pdfa.enabled` | `false` | Enable PDF/A-2b output (adds XMP metadata, sRGB OutputIntent) |
| `pdf.pdfa.fontPath` | `""` | Path to a TrueType font for embedding in PDF/A; empty = Standard 14 (non-embedded) |
| `output.file` | `output.pdf` | Default output file path |
| `jbig2enc.flags` | `-p -s` | Flags passed to the jbig2enc binary |

CLI flags that override corresponding settings: `--dpi`, `--language`, `--psm`, `--no-mrc`, `--pdfa`, `--threads`, `--txt-output`, `--bbox-output`, `--native-dir`, `--tessdata-dir`, `-o`/`--output`, `--settings` (path to a custom settings file).

---

## OCR Quality

| Metric | Regular prose pages | Notes |
|---|---|---|
| **Word Error Rate** | 3.9% – 6.8% | Aggregate 5.4% on 10-page Sherlock Holmes corpus |
| **Character Error Rate** | 1.0% – 2.1% | Sub-character accuracy on clean scans |
| **Word Recall** | 94.0% – 99.0% | Almost all ground-truth words detected |
| **Word Precision** | 94.9% – 99.0% | Very few false positives |
| **Mean Confidence** | 90.0 – 93.6 | Tesseract per-word confidence score |

Results measured on the 10-page Sherlock Holmes prose corpus at 300 DPI, English language.
The corpus is standard prose throughout — the WER range reflects normal variation across
pages. See [`docs/Evaluation.md`](docs/Evaluation.md) for the full methodology, per-page
breakdown, and parameter sensitivity plans.

---

## Testing

Tests use sample PDFs in `tests/test-files/`:
- `generated/` — synthetic PDFs produced by `TestPdfGenerator.java`
- `real-world/` — downloaded publicly-available PDFs (research papers, government publications, business reports)

Regenerate test PDFs with:
```bash
./gradlew generateTestPdfs
```

See [`docs/Evaluation.md`](docs/Evaluation.md) for the OCR accuracy evaluation plan —
WER/CER targets, parameter sensitivity sweeps, and performance baselines.

---

## Components

| Component | What it does |
|---|---|
| **PageExtractor** | Renders each PDF page to a `BufferedImage` at configurable DPI using PDFBox's `PDFRenderer.renderImageWithDPI()`. For image inputs, pages are loaded directly via `ImageIO`. |
| **ImageSegmenter** | Pure-Java page segmentation: grayscale conversion → background normalization → Otsu binarization → inpainting. No Leptonica dependency. |
| **OCREngine** | Delegates to Tesseract CLI (not JNA/Tess4J) via subprocess with TSV output. Avoids native-library version mismatches. |
| **JBIG2Compressor** | Compresses binary foreground masks via jbig2enc (`-p -s`). Falls back to CCITT Group 4 fax encoding via PDFBox when jbig2enc is unavailable. |
| **PDFAssembler** | Re-assembles the output PDF: background JPEG (quality 0.50 with MRC, 0.85 without) + CCITT G4 / JBIG2 foreground stencil + invisible OCR text (rendering mode 3, Standard 14 fonts). Copies bookmarks, annotations, and XMP metadata via MetadataPreserver. |
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
The `bootstrap.sh` / `bootstrap.bat` script handles setting them up from scratch
with no admin rights required.

| Dependency | Project Location | License | Project |
|---|---|---|---|
| OpenJDK 21 LTS | `deps/jdk/` | GPL 2.0 + Classpath Exception | https://adoptium.net |
| Gradle 8.0.1 | `deps/gradle/` | Apache 2.0 | https://gradle.org |
| Tesseract OCR | `deps/tesseract/$OS/` (binary + libs) | Apache 2.0 | https://github.com/tesseract-ocr/tesseract |
| Leptonica | `deps/tesseract/$OS/lib/liblept.so.5`, also copied to `deps/jbig2enc/$OS/lib/` | BSD 2-Clause | https://github.com/DanBloomberg/leptonica |
| jbig2enc | `deps/jbig2enc/$OS/` (binary + lib) | Apache 2.0 | https://github.com/agl/jbig2enc |
| Tesseract language data | `deps/tesseract/tessdata/*.traineddata` (eng, fra, spa, deu, chi_sim, chi_tra, jpn, osd) | Apache 2.0 | https://github.com/tesseract-ocr/tessdata |

<details>
<summary>Why GPL + Classpath Exception is business-friendly</summary>

The Classpath Exception (CPE) attached to GPL 2.0 means that code you run *on* OpenJDK — including TrulyFreeOCR's JAR and your own application code — is **not** subject to GPL copyleft. Only modifications to OpenJDK itself would need to be open-sourced. For practical purposes, using the JDK as a runtime imposes no license obligations on your project or your code. OpenJDK builds from Adoptium (Eclipse Temurin), Oracle, and all major vendors use this same GPL+CPE license.

References:
[OpenJDK GPLv2+CE](https://openjdk.org/legal/gplv2+ce.html) —
full exception text, including permission to link with independent modules under any license terms;
[Adoptium FAQ](https://adoptium.net/docs/faq/) —
confirms Temurin is free to use commercially under GPLv2+CE;
[Fedora Wiki](https://fedoraproject.org/wiki/Licensing/GPL_Classpath_Exception) —
explains that using the class library does not affect licensing of programs written in Java.
</details>

Binary wrappers in `deps/tesseract/$OS/` and `deps/jbig2enc/$OS/` set `LD_LIBRARY_PATH` so
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
| 13 | 7 language bundles | Done |

</details>

## License

TrulyFreeOCR itself is made available under the Apache 2.0 License. All dependencies listed above use permissive open-source licenses compatible with commercial use. A full `NOTICE.md` with copyright attributions is included in the repository.

## Acknowledgements

This project was developed with the assistance of multiple AI coding models like [DeepSeek](https://deepseek.com) and [Gemini](https://gemini.google.com), and AI coding tools including [OpenCode](https://opencode.ai) and [Antigravity](https://github.com/antigravity).
