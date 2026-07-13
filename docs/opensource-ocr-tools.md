# Comparison of Open-Source OCR Tools

## Overview

A survey of open-source tools that produce searchable, compressed PDFs from scanned
documents or input PDFs.  The focus is on license compatibility, MRC compression,
self-contained deployment, and Java portability.

---

## Traditional OCR & PDF Tools

| Feature | TrulyFreeOCR | OCRmyPDF | archive-pdf-tools | pdf2pdfocr | Docling | PaddleOCR | MinerU | Marker |
|---|---|---|---|---|---|---|---|---|
| **Language** | Java 21 (pure) | Python | Python | Python | Python | Python / C++ | Python | Python |
| **License** | Apache 2.0 | MPL-2.0 | AGPL-3.0 | Apache 2.0 | MIT | Apache 2.0 | Apache 2.0 + custom terms | GPL-3.0 |
| **GitHub Stars** | — | ~29k | ~140 | ~300 | ~62k | ~84k | ~74k | ~20k |
| **OCR Engine** | Tesseract 5 CLI | Tesseract 5 | Tesseract 5 | Tesseract | Tesseract / pluggable | PaddleOCR (own) | MinerU (own) | surya + Tesseract |
| **MRC Compression** | Yes (PNG bg + JBIG2/G4 fg) | Yes (via `--optimize`) | Yes (JPEG2000 + JBIG2) | No | No (extraction only) | No | No | No |
| **Self-Contained** | Yes (single JAR) | No | No | No | No | No | No | No |
| **PDF/A** | Yes (2b) | Yes (1b/2b/3b) | Yes (3b) | No | Extraction only | No | No | No |
| **No Native Deps** | Only Tesseract | Ghostscript + Tesseract | Kakadu/OpenJPEG + MuPDF | pdftk + qpdf + gs | Models at runtime | PyTorch / GPU | Models at runtime | Models at runtime |
| **Image Preprocessing** | Otsu + background norm. | Deskew, clean, rotate | Sauvola binarization | None | Vision-based layout | DL-based | Layout analysis | Layout analysis |
| **Concurrent OCR** | Optional (configurable) | Yes (multi-core) | Yes | Yes | Yes (batching) | Yes (GPU batch) | Yes (batching) | Yes |
| **Language Support** | 20 languages | 100+ languages | 100+ languages | 100+ languages | 100+ languages | 100+ languages | 109 languages | 90+ languages |
| **CLI Interface** | picocli | argparse | Custom | argparse | Typer | Custom | Custom | Custom |
| **Active Development** | Yes | Yes (v17.7.0) | Stale (2021) | Low (2023) | Yes (v2.x) | Yes (v3.7.0) | Yes (v1.x) | Yes (v1.x) |

## Vision Language Model (VLM) / End-to-End OCR Models

| Feature | DeepSeek-OCR | GOT-OCR2.0 | GLM-OCR | MonkeyOCR | Unlimited-OCR | olmOCR | Chandra OCR |
|---|---|---|---|---|---|---|---|
| **Language** | Python | Python | Python | Python | Python | Python | Python |
| **License** | MIT | Apache 2.0 (code), research-only (weights) | MIT (model), Apache 2.0 (code) | Alibaba non-commercial (weights), Apache 2.0 (code) | MIT | Apache 2.0 | Apache 2.0 (code), modified OpenRAIL-M (weights) |
| **GitHub Stars** | ~23k | ~8k | ~7k | ~6k | ~11k | ~4k | ~11.5k |
| **OCR Engine** | VLM (3B) | VLM (580M) | VLM (0.9B) | VLM (1.2B / 3B) | VLM (3B) | VLM (7B) | VLM (4B) |
| **MRC Compression** | No | No | No | No | No | No | No |
| **Self-Contained** | No (Python + GPU) | No (Python + GPU) | No (Python + GPU) | No (Python + GPU) | No (Python + GPU) | No (Python + GPU) | No (Python + GPU) |
| **PDF/A** | No | No | No | No | No | No | No |
| **Output Format** | Plain text | Plain / formatted text | Markdown / JSON | Markdown / JSON | Markdown | Plain text | Markdown / JSON |
| **GPU Required** | Yes (A100-40G) | Yes (24GB+) | Yes (4GB VRAM) | Yes (3090+) | Yes (24GB+) | Yes (FP8) | Yes (24GB+) |
| **Speed** | ~2500 tokens/s (A100) | — | 1.86 pages/s | ~1 page/s (4090) | 1+ page/s (A100) | — | ~1 page/s |
| **Active Development** | Yes (Oct 2025) | Moderate (2024) | Yes (Feb 2026) | Yes (Jun 2025) | Yes (Jun 2026) | Low (2025) | Yes (2026) |

## Deep Learning OCR Toolkits & Desktop Tools

| Feature | EasyOCR | surya | docTR | RapidOCR | Umi-OCR | NAPS2 | PandaOCR | SwiftOCR | llama_index | tesseract.js | paperless-ngx | Unstructured |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **Language** | Python | Python | Python | Python / C++ / Java / C# | Python + QML | C# (.NET) | AutoIt / Pascal | Swift | Python / TypeScript | JavaScript / Node | Python + Django | Python |
| **License** | Apache 2.0 | Apache 2.0 (code), OpenRAIL-M (weights) | Apache 2.0 | Apache 2.0 | MIT | GPL 2.0 | Custom freeware | Apache 2.0 | MIT | Apache 2.0 | GPL-3.0 | Apache 2.0 |
| **GitHub Stars** | ~30k | ~30k | ~6k | ~7k | ~46k | ~4.3k | ~5k | ~5k | ~50k | ~36k | ~42k | ~10k |
| **OCR Engine** | CRAFT + CRNN (PyTorch) | Own VLM (650M) | DBNet + CRNN / ViT | PaddleOCR → ONNX | PaddleOCR / RapidOCR | Tesseract | Cloud API aggregator | NN (Swift) | LlamaParse (cloud) | Tesseract (WASM) | Tesseract | Tesseract / pluggable |
| **MRC Compression** | No | No | No | No | Basic (dual-layer PDF) | No | No | No | No | No | No | No |
| **Self-Contained** | No (Python + PyTorch) | No (Python + GPU) | No (Python + PyTorch) | No (Python) | Yes (exe + runtime) | Portable archive | Yes (Windows exe) | Yes (Swift pkg) | No (Python / cloud API) | Yes (npm package) | No (Python + web server) | No (Python) |
| **PDF/A** | No | No | No | No | No | No | No | No | No | No | No | No |
| **Searchable PDF** | No | No | No | No | Yes (basic dual-layer) | Yes (via OCR) | No | No | No | No | Yes | No |
| **Concurrent OCR** | Yes (GPU batch) | Yes (GPU batch) | Yes (GPU batch) | Yes | Yes | No | N/A (sequential) | No | Yes (cloud) | Yes (workers) | Yes (celery) | Yes (batching) |
| **Active Development** | Moderate (v1.7.2) | Yes (v0.20.0) | Moderate | Yes (v3.9.0) | Yes (v2.1.5) | Yes (v8.2.1) | Yes (Pro v5.59) | Deprecated (2020) | Yes (weekly) | Moderate | Yes (v2.x) | Yes |

---

## Detailed Analysis

### OCRmyPDF (ocrmypdf/OCRmyPDF)
- **License**: MPL-2.0 — business-friendly, allows closed-source integration if
  modifications to OCRmyPDF itself are published.
- **Strengths**: Gold standard for PDF OCR.  Battle-tested on millions of PDFs.
  Rich preprocessing (deskew, clean, rotate).  Full PDF/A support.  100+ languages.
  Plugin architecture (AppleOCR, EasyOCR, PaddleOCR backends).
- **Weaknesses**: Python + system dependencies (Ghostscript, Tesseract).  Not
  self-contained.  MPL-2.0 requires source disclosure of modifications to
  OCRmyPDF code.  Ghostscript is AGPL (used as subprocess, not linked).
- **Relevance**: Directly comparable in output quality and feature set.  Our
  advantage is Java portability (single JAR) and Apache 2.0 license (no
  publication requirement).

### archive-pdf-tools (internetarchive/archive-pdf-tools)
- **License**: AGPL-3.0 — **not compatible** with commercial use without
  disclosing entire application source.
- **Strengths**: Mature MRC compression with JPEG2000.  Proven at scale (6M PDFs
  in 2021).  PDF/A-3b and PDF/UA support.  Mask viewing tools.
- **Weaknesses**: AGPL-3.0 is a non-starter for commercial use.  Requires
  system JPEG2000 codec (Kakadu or OpenJPEG).  Python + complex dependency tree.
  Stale (last release 2021).
- **Relevance**: Shows that MRC + OCR at Internet Archive scale is feasible.
  Our MRC approach (PNG bg + JBIG2/G4 fg) is simpler and avoids JPEG2000
  licensing issues.

### pdf2pdfocr (LeoFCardoso/pdf2pdfocr)
- **License**: Apache 2.0 — same as ours.
- **Strengths**: Simple, straightforward.  No re-encoding of source images when
  possible.  GUI available.  Cross-platform (Windows batch script included).
- **Weaknesses**: No MRC compression.  No PDF/A.  No image preprocessing.
  Low community (303 stars).  1.2.0 release never formalized.
- **Relevance**: Demonstrates the simplest possible OCR sandwich approach.
  Our tool adds MRC compression and full PDF/A for a material improvement.

### Docling (docling-project/docling)
- **License**: MIT — permissive.
- **Strengths**: IBM Research quality.  Vision-based layout analysis (avoids
  pixel-level OCR for clean PDFs).  TableFormer for table extraction.  30x faster
  than OCR for born-digital PDFs.  Huge ecosystem (LangChain, LlamaIndex).
  61k+ stars.
- **Weaknesses**: Focused on *extraction* to Markdown/JSON, not on producing
  compressed searchable PDFs.  Heavy model downloads (GBs).  Python-only.
  Requires PyTorch for local inference.
- **Relevance**: Complementary — Docling is for RAG pipelines, not for
  producing archive-quality PDF output.  If we ever add a structure-preserving
  extraction mode, Docling's approach (vision models for layout) would be the
  direction.

### PaddleOCR (PaddlePaddle/PaddleOCR)
- **License**: Apache 2.0.
- **Strengths**: SOTA accuracy (reported better than Tesseract for many
  scripts).  GPU-accelerated.  84k+ stars.  LLM-ready output (JSON/Markdown).
  Mature document parsing pipeline.
- **Weaknesses**: Python + PyTorch/PaddlePaddle runtime.  GPU strongly
  recommended for practical throughput.  Model downloads are large.  No
  searchable PDF output (focused on text extraction).
- **Relevance**: PaddleOCR is the best OCR *engine* available today.  If we
  ever add a pluggable OCR backend, PaddleOCR CLI (via subprocess, like
  Tesseract) would be a natural second option for higher accuracy.

### olmOCR (allenai/olmOCR)
- **License**: Apache 2.0 (model weights, code).
- **Strengths**: VLM-based OCR that preserves reading order, handles tables,
  equations, handwriting.  Designed for high-throughput (estimated $178/1M pages).
- **Weaknesses**: Requires GPU (FP8 inference).  7B parameter model.  Very new
  (2025/2026).  No searchable PDF output — text-only.
- **Relevance**: VLM-based OCR is the emerging paradigm.  Once these models
  become efficient enough to run on CPU, a subprocess-based integration would
  be straightforward.

### EasyOCR (JaidedAI/EasyOCR)
- **License**: Apache 2.0.
- **Strengths**: Ready-to-use with 80+ languages.  CRAFT-based text detection +
  CRNN recognition.  GPU and CPU support.  Active community (~30k stars).
  Simple Python API.  Custom model training support.
- **Weaknesses**: Python + PyTorch dependency.  Model downloads (hundreds of MB).
  No searchable PDF output.  No MRC compression.  Accuracy lags behind modern
  VLM approaches for complex layouts.
- **Relevance**: The most popular general-purpose OCR library.  If we add a
  Python subprocess-based pluggable OCR backend, EasyOCR would be a candidate
  for non-Tesseract engines (alongside PaddleOCR).

### surya (VikParuchuri/surya, now datalab-to/surya)
- **License**: Apache 2.0 (code), modified OpenRAIL-M (weights — free for
  research/personal/startups under $5M funding/revenue).  Commercial use beyond
  limits requires license from Datalab.
- **Strengths**: 650M param model scoring 83.3% on olmOCR-bench (top under 3B
  params).  5 pages/s on RTX 5090.  90+ languages.  Includes layout analysis,
  reading order, table recognition.  CPU, GPU, MPS support.
- **Weaknesses**: Python + GPU recommended.  Model weights have commercial
  restrictions.  Weighted licensing model complicates commercial use.  No
  searchable PDF output.  No MRC compression.
- **Relevance**: Surya represents the best lightweight VLM OCR available.
  Its layout analysis and reading order are superior to Tesseract for complex
  documents.  If the weight licensing were fully Apache 2.0, it would be the
  ideal Tesseract replacement for a pluggable backend.

### docTR (mindee/doctr)
- **License**: Apache 2.0.
- **Strengths**: Two-stage (detection + recognition) OCR with PyTorch and
  TensorFlow backends.  Swappable architectures (DBNet, CRNN, ViTSTR, PARSeq,
  MASTER).  Comparable accuracy to Google Vision / AWS Textract on public
  benchmarks.  CPU and GPU support.  FastAPI integration.
- **Weaknesses**: Python + PyTorch/TensorFlow dependency.  No searchable PDF
  output.  No MRC compression.  Smaller community (~6k stars).  No built-in
  layout analysis beyond text detection.
- **Relevance**: A well-engineered, modular OCR library.  Its architecture
  flexibility makes it useful for research and custom OCR pipelines, but it
  lacks the document-to-PDF output pipeline we provide.

### RapidOCR (RapidAI/RapidOCR)
- **License**: Apache 2.0.
- **Strengths**: PaddleOCR models converted to ONNX for cross-platform deployment.
  Multi-language support (Python, C++, Java, C#, Go).  Multiple inference backends
  (ONNXRuntime, OpenVINO, PaddlePaddle, PyTorch).  Very fast (3.3M monthly pip
  downloads).  Active development (v3.9.0).  Good for Chinese + English.
- **Weaknesses**: Focused on text detection + recognition only.  No searchable
  PDF output.  No MRC compression.  No layout analysis beyond basic detection.
  Primarily optimized for Chinese/English (smaller language coverage).
- **Relevance**: RapidOCR is the fastest pure-OCR library available.  Its Java
  binding is interesting for JVM integration, but it lacks the full PDF pipeline
  (compression, PDF/A, text layer) that TrulyFreeOCR provides.

### DeepSeek-OCR (deepseek-ai/DeepSeek-OCR)
- **License**: MIT (code and weights).
- **Strengths**: "Contexts Optical Compression" — encodes text-rich images into
  compact vision tokens (64-400 tokens for 600-5000+ text tokens).  ~97%
  accuracy.  200k+ pages/day on a single A100.  Nearly 100 languages.  3B param
  model.  Very popular (~23k stars in 8 months).
- **Weaknesses**: Python + large GPU (A100-40G) for practical throughput.
  No searchable PDF output.  No MRC compression.  Research-oriented (no
  production packaging).  Very new (October 2025).
- **Relevance**: DeepSeek-OCR pioneered the VLM-as-OCR paradigm.  Its
  compression technique is conceptually aligned with our MRC approach (compress
  while preserving information).  The model itself could serve as a Tesseract
  replacement for highest-accuracy needs, given sufficient hardware.

### GOT-OCR2.0 (Ucas-HaoranWei/GOT-OCR2.0)
- **License**: Apache 2.0 (code, HuggingFace weights), research-only (original
  weights from Vary license).
- **Strengths**: First unified "OCR-2.0" end-to-end model.  580M parameters
  (smaller than DeepSeek-OCR).  Handles plain text, formulas, tables, charts,
  sheet music, geometric shapes.  Region-level recognition via coordinates or
  colors.  Multi-crop for high-resolution images.  Dynamic resolution.
- **Weaknesses**: Python + GPU (24GB+).  Research-only license on original
  weights (though HF version uses Apache 2.0).  No searchable PDF output.  No
  MRC compression.  Last code update December 2024.
- **Relevance**: GOT-OCR2.0 demonstrated that a single end-to-end model could
  replace the traditional detection+recognition pipeline.  Its approach
  influenced DeepSeek-OCR, GLM-OCR, and the entire VLM OCR trend.

### GLM-OCR (zai-org/GLM-OCR)
- **License**: MIT (model), Apache 2.0 (code).
- **Strengths**: State-of-the-art on OmniDocBench V1.5 (94.62).  Only 0.9B
  parameters — runs on 4GB VRAM.  1.86 pages/second.  Multi-Token Prediction
  (MTP) loss and RL training.  CogViT visual encoder.  PP-DocLayout-V3 layout
  analysis.  Excellent for complex tables, code documents, seals.  vLLM,
  SGLang, Ollama support.
- **Weaknesses**: Python + GPU required.  No searchable PDF output.  No MRC
  compression.  Very new (February 2026).  Pipeline depends on PP-DocLayout-V3
  (Apache 2.0).
- **Relevance**: GLM-OCR achieves the best accuracy/size ratio of any VLM OCR
  model.  Its 0.9B parameter size and 4GB VRAM requirement make it the most
  accessible high-accuracy OCR model.  Ideal candidate for a future pluggable
  VLM backend once GPU requirements decrease further.

### MonkeyOCR (Yuliang-Liu/MonkeyOCR)
- **License**: Apache 2.0 (code), Alibaba non-commercial license (weights).
- **Strengths**: Structure-Recognition-Relation (SRR) triplet paradigm.
  Surpasses GPT-4o, Gemini 2.5-Pro on OmniDocBench.  ~1 page/s on RTX 4090.
  MonkeyOCR-pro-1.2B leaner version available.  MonkeyDoc dataset (3M+ pages).
  Chinese and English support.  Cross-page table reconstruction (v1.5).
- **Weaknesses**: Alibaba non-commercial weight license — **not suitable for
  commercial use**.  Python + GPU required.  No searchable PDF output.  No MRC
  compression.  Weight licensing restricts practical deployment.
- **Relevance**: Among the most accurate document parsers available, but the
  non-commercial weight license makes it unusable for our business-friendly
  requirement.  Demonstrates the SRR paradigm for document understanding.

### Unlimited-OCR (baidu/Unlimited-OCR)
- **License**: MIT.
- **Strengths**: One-shot long-horizon parsing (entire multi-page document in
  single pass).  Sliding window memory prevents OOM on long documents.  3B params,
  32,768 token context.  Built on DeepSeek-OCR innovations.  MIT license is fully
  business-friendly.  11k+ stars in one week (June 2026).
- **Weaknesses**: Just released (June 22, 2026) — very early.  Python + GPU
  required.  No searchable PDF output.  No MRC compression.  Ecosystem and
  tooling still immature.
- **Relevance**: The most promising recent VLM OCR entry.  Its one-shot long
  document capability and MIT license make it ideal for a future pluggable
  backend once VLM OCR matures and GPU requirements decrease.  Baidu's
  reputation ensures ongoing development.

### Umi-OCR (hiroi-sora/Umi-OCR)
- **License**: MIT.
- **Strengths**: Fully offline desktop OCR app (Windows/Linux).  Screenshot OCR,
  batch processing, PDF recognition with dual-layer searchable PDF output.
  100+ languages via PaddleOCR or RapidOCR engines.  QR/barcode scanning.
  Formula recognition (LaTeX).  No GPU required.  46k stars.  Portable (unzip and
  run).  HTTP API for external integration.
- **Weaknesses**: Desktop GUI app — not designed for server/automation use.
  Dual-layer PDF output is basic (no MRC compression, no PDF/A).  Windows-focused
  (Linux support experimental).  Python runtime bundled (large download).
  No CLI-first design.
- **Relevance**: Umi-OCR is the most comparable tool for *desktop* searchable
  PDF creation.  Its dual-layer PDF output proves demand for this feature.
  Our tool targets the server/enterprise space where Umi-OCR cannot operate.

### NAPS2 (cyanfish/naps2)
- **License**: GPL 2.0 — strong copyleft, incompatible with commercial
  integration without disclosing the entire application source.  SDK modules
  are LGPL 2.1, which permits library-style linking.
- **Strengths**: Best-in-class scanner driver support (WIA, TWAIN, SANE, ESCL).
  Clean desktop GUI across Windows, Mac, and Linux.  CLI and .NET SDK for
  automation and integration.  40+ language translations.  Scanner sharing over
  network.  Active development (8+ years, 4,400+ commits, 4.3k stars).
  Portable archive available (no installer needed).
- **Weaknesses**: GPL 2.0 restricts commercial embedding.  Desktop GUI app —
  not designed for headless server/automation batch processing.  No MRC
  compression.  No PDF/A.  Requires .NET runtime (not self-contained in the
  same sense as a single fat JAR).  OCR (via Tesseract) is a secondary feature
  — scanning is the primary focus.
- **Relevance**: NAPS2 is the most polished open-source scanner application
  with OCR capability, but it serves a fundamentally different use case —
  interactive scanning, not automated PDF batch processing.  TrulyFreeOCR
  complements NAPS2 in the server/automation space where headless operation,
  MRC compression, and PDF/A compliance are required.

### PandaOCR (miaomiaosoft/PandaOCR / PandaOCR.Pro)
- **License**: Custom freeware — not open source (source code not provided on
  GitHub, only binary releases).  PandaOCR.Pro has paid "Pro" activation.
- **Strengths**: Multi-engine OCR aggregator (Baidu, Tencent, Sogou, Youdao, Ali,
  iFLYTEK, Mathpix, etc.).  Screenshot OCR, translation, TTS, formula/table
  recognition.  Game translation.  QR/barcode scanning.  Image search.
  Active development (Pro v5.59, June 2026).  7+ years of updates.
- **Weaknesses**: Not open source (binary-only releases).  Relies on cloud API
  keys (no offline OCR).  Windows-only.  Free version has limitations; Pro
  requires payment.  No searchable PDF output.  No MRC compression.  Privacy
  concerns (uploads to third-party APIs).
- **Relevance**: Demonstrates user demand for multi-engine OCR aggregation.
  Our approach (single bundled Tesseract + optional VLM backends) avoids the
  complexity of managing multiple cloud API keys and the associated privacy
  risks.

### SwiftOCR (NMAC427/SwiftOCR)
- **License**: Apache 2.0.
- **Strengths**: Native Swift OCR library for iOS/macOS.  Neural network-based.
  Simple API (6 lines of code).  Good for short alphanumeric codes (serial
  numbers, gift cards).  GPUImage acceleration.
- **Weaknesses**: **Deprecated** (last commit May 2020).  Only recognizes short
  alphanumeric text — not suitable for document OCR.  iOS/macOS only.  No
  searchable PDF output.  No MRC compression.  No longer maintained.
- **Relevance**: Minimal — deprecated and limited in scope.  Included for
  completeness but not a relevant comparison to our document PDF pipeline.

### llama_index (run-llama/llama_index + LlamaParse)
- **License**: MIT (core framework).  LlamaParse is a cloud service with paid
  tiers (free tier: 10k credits/month ≈ 1k pages).
- **Strengths**: Leading RAG framework (50k+ stars).  LlamaParse provides
  agentic OCR with layout-aware parsing, multi-tier strategy (Fast, Cost
  Effective, Agentic).  90+ file types.  Multi-page table handling, embedded
  images, handwritten notes.  Python + TypeScript SDKs.  Java SDK available.
  Deep LLM ecosystem integration.
- **Weaknesses**: LlamaParse is a cloud service (not self-contained).  Paid per
  page ($1-15/1k pages).  No MRC compression.  No PDF/A output.  Focused on
  extraction for RAG, not archive-quality PDF production.  Framework is
  Python-heavy for self-hosted use.
- **Relevance**: LlamaIndex/LlamaParse represents the cloud/API approach to
  document OCR.  Our tool competes in the self-contained, offline, business-friendly
  space.  If users want cloud OCR with RAG pipelines, LlamaParse is the best
  option; if they need offline batch PDF production with MRC compression, we
  provide that.

### MinerU (opendatalab/MinerU)
- **License**: Apache 2.0 + custom terms (free for <100M MAU / <$20M revenue).
- **Strengths**: PDF/DOCX/PPTX/XLSX to Markdown/JSON.  Layout analysis with
  layout detection + formula/table extraction.  109 languages.  CLI, API, and
  WebUI.  Very popular (~74k stars).  Active development.
- **Weaknesses**: Python + model downloads required.  No searchable PDF output.
  No MRC compression.  No PDF/A.  Custom license terms restrict very large
  commercial deployments.
- **Relevance**: The most popular document extraction tool.  Complementary in
  purpose — MinerU converts to Markdown/JSON for RAG, while we produce
  archive-quality searchable PDFs.  A `--extract-json` mode in our tool could
  learn from MinerU's layout pipeline.

### Marker (VikParuchuri/marker)
- **License**: GPL-3.0 — strong copyleft, incompatible with commercial
  integration without disclosing entire application source.
- **Strengths**: PDF to Markdown with high accuracy.  Uses surya for layout
  analysis and Tesseract for OCR.  GPU and CPU support.  Handles tables, code
  blocks, images.  Good reading order preservation.
- **Weaknesses**: GPL-3.0 is a non-starter for commercial use.  No searchable
  PDF output.  No MRC compression.  Python + model dependencies.  Focused on
  extraction, not PDF production.
- **Relevance**: Demonstrates the surya + Tesseract combination for high-quality
  extraction.  If we ever add a pluggable backend, surya-powered layout analysis
  (as used by Marker) would be a strong candidate for structure preservation.

### Chandra OCR (chandra-ai/chandra-ocr)
- **License**: Apache 2.0 (code), modified OpenRAIL-M (weights — free for
  research and startups under $2M revenue/funding).
- **Strengths**: 4B param VLM scoring 85.9% on olmOCR-bench.  Handles
  handwriting, tables, layout, reading order.  43 languages.  Designed for
  high-throughput document processing.
- **Weaknesses**: Python + GPU (24GB+) required.  Modified OpenRAIL-M weight
  license restricts commercial use beyond startup scale.  No searchable PDF
  output.  No MRC compression.  Very new (2026).
- **Relevance**: Among the most accurate VLM OCR models available.  Its 4B
  parameter size and strong benchmark scores make it a compelling candidate for
  a future pluggable VLM backend once weight licensing becomes more permissive.

### tesseract.js (naptha/tesseract.js)
- **License**: Apache 2.0 — same as ours.
- **Strengths**: Tesseract OCR running in the browser and Node.js via WebAssembly.
  100+ languages.  No native dependencies — pure JavaScript.  Simple API.
  Very popular (~36k stars).  Works offline (once models are loaded).
- **Weaknesses**: Slower than native Tesseract (WASM overhead).  No searchable
  PDF output.  No MRC compression.  Browser-focused (limited CLI/server use).
  Inherits Tesseract's limitations (no layout analysis, poor handwriting).
- **Relevance**: Proves that Tesseract can run in non-traditional environments.
  Our Java subprocess approach offers better performance, but tesseract.js shows
  demand for OCR in JS/Node ecosystems.

### paperless-ngx (paperless-ngx/paperless-ngx)
- **License**: GPL-3.0 — strong copyleft.
- **Strengths**: Full-featured self-hosted document management system.  Tesseract
  OCR with ML-based classification and tagging.  REST API, web UI, consumer
  directory (watched folder).  Django + Celery for scalable background
  processing.  Very popular (~42k stars).  Active development (v2.x).
- **Weaknesses**: GPL-3.0 restricts commercial embedding.  Full stack
  (Django + DB + Redis + web server) — not self-contained.  No MRC compression.
  No PDF/A.  Designed as a DMS, not a batch OCR tool.
- **Relevance**: The most popular self-hosted DMS with OCR.  Demonstrates
  demand for automated document processing.  Our tool targets the OCR+PDF
  pipeline layer — complementary to paperless-ngx's DMS features.  paperless-ngx
  could use TrulyFreeOCR as its OCR backend for MRC-compressed output.

### Unstructured (Unstructured-IO/unstructured)
- **License**: Apache 2.0 — business-friendly.
- **Strengths**: Document ETL for LLM/RAG pipelines.  Multi-format partitioning
  (PDF, DOCX, HTML, images, etc.).  Table extraction, chunking, cleaning.
  Cloud API and local modes.  Connectors for S3, Azure, Google Drive, etc.
  Active development (~10k stars).
- **Weaknesses**: Python + model downloads for local mode.  No searchable PDF
  output.  No MRC compression.  No PDF/A.  Focused on extraction for LLM
  ingestion, not archive-quality PDF production.  Cloud API has usage costs.
- **Relevance**: Unstructured is the standard tool for preparing documents for
  RAG pipelines.  Our tool addresses the complementary problem — producing
  compressed, searchable, PDF/A-compliant archives.  A user needing both would
  use Unstructured for LLM ingestion and TrulyFreeOCR for archival PDF output.

---

## Why TrulyFreeOCR Exists

| Problem | Existing Tools | Our Solution |
|---|---|---|
| Business-friendly license | OCRmyPDF (MPL-2.0) requires publishing modifications; archive-pdf-tools is AGPL; MonkeyOCR has non-commercial weights; surya has restricted weights | Apache 2.0 — no disclosure obligations; all deps have business-friendly licenses |
| Self-contained deployment | All alternatives require Python + system libs + model downloads or cloud APIs | Single fat JAR + bundled native binaries + bootstrap script |
| Java/enterprise integration | Python tools need subprocess calls from Java code | Native Java API, embeddable in Maven/Gradle projects |
| MRC compression | Only OCRmyPDF and archive-pdf-tools offer it, with complex deps; Umi-OCR offers basic dual-layer | PNG bg + JBIG2/G4 fg with clean fallback |
| No Ghostscript dependency | OCRmyPDF requires Ghostscript (AGPL) | Pure PDFBox — no AGPL anywhere in the stack |
| VLM future-proofing | Current VLM models (DeepSeek-OCR, GLM-OCR, Unlimited-OCR) require GPU and produce text only | Architecture supports pluggable backends; Tesseract is performant on CPU today |
| Offline/privacy | PandaOCR, LlamaParse send data to cloud APIs; EasyOCR/docTR need Python runtime | Fully offline; everything runs locally; no data leaves the machine |

## Potential Future Directions

1. **Pluggable OCR backends** — Add PaddleOCR CLI or RapidOCR as alternatives to
   Tesseract (subprocess-based, same pattern).  Once VLM models (GLM-OCR,
   Unlimited-OCR) become CPU-efficient, add them as higher-accuracy backends.
2. **Deskew/clean preprocessing** — Add Leptonica-based deskew and text
   cleaning via subprocess (similar to OCRmyPDF's `--deskew`).
3. **JPEG2000 background** — Add optional JPEG2000 background compression
   via OpenJPEG subprocess for better compression ratios than PNG.
4. **Docling-style extraction** — A `--extract-json` mode that outputs
   structured Markdown/JSON for RAG pipelines.
5. **Umi-OCR-style desktop GUI** — If desktop demand grows, a lightweight
   GUI wrapper around the CLI (like Umi-OCR but Java-based) could serve
   non-technical users.
