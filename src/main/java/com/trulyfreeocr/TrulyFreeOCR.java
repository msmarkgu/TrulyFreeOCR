package com.trulyfreeocr;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;

import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import com.trulyfreeocr.model.PageResult;
import com.trulyfreeocr.model.SegmentedImage;
import com.trulyfreeocr.model.TextBlock;
import com.trulyfreeocr.pipeline.ImageSegmenter;
import com.trulyfreeocr.pipeline.JBIG2Compressor;
import com.trulyfreeocr.pipeline.OCREngine;
import com.trulyfreeocr.pipeline.PDFAssembler;
import com.trulyfreeocr.util.Settings;

@Command(
    name = "trulyfreeocr",
    version = "1.0.0",
    description = "Commercially-safe OCR pipeline for non-searchable PDFs",
    mixinStandardHelpOptions = true
)
public class TrulyFreeOCR implements Callable<Integer> {

    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
        "png", "jpg", "jpeg", "tif", "tiff", "bmp", "gif"
    );

    @Parameters(index = "0", description = "Input file (PDF or image)")
    private File inputFile;

    @Option(names = {"-o", "--output"}, description = "Output PDF file")
    private File outputFile;

    @Option(names = {"--native-dir"}, description = "Directory containing native binaries")
    private File nativeDir;

    @Option(names = {"--tessdata-dir"}, description = "Directory containing Tesseract language data")
    private File tessdataDir;

    @Option(names = {"--tesseract-path"}, description = "Path to Tesseract executable")
    private String tesseractPath;

    @Option(names = {"--settings"}, description = "Path to settings.jsonc file")
    private File settingsFile;

    @Option(names = {"--dpi"}, description = "Rendering DPI for PDF page images")
    private Float dpi;

    @Option(names = {"--language"}, description = "Tesseract language model")
    private String language;

    @Option(names = {"--psm"}, description = "Tesseract page segmentation mode")
    private String psm;

    @Option(names = {"--no-mrc"}, description = "Disable MRC compression (output original images + text layer only)")
    private boolean noMrc;

    @Option(names = {"--pdfa"}, description = "Enable PDF/A-2b output (XMP metadata, sRGB OutputIntent)")
    private Boolean pdfa;

    @Option(names = {"--threads"}, description = "Worker threads for prep + OCR. Default: pipeline.ocr.maxThreads (1).")
    private Integer threads;

    @Option(names = {"--txt-output"}, description = "Path for extracted text output (default: <output>.txt)")
    private File txtOutput;

    @Option(names = {"--bbox-output"}, description = "Path for word bounding box JSON output (default: <output>.json)")
    private File bboxOutput;

    @Override
    public Integer call() {
        Settings settings = Settings.load();
        if (settingsFile != null) {
            System.setProperty("tfocr.settings", settingsFile.getAbsolutePath());
            settings = Settings.load();
        }

        System.out.println();
        System.out.println("TrulyFreeOCR v1.0.0");
        System.out.println("  Input:  " + inputFile + " (" + formatSize(inputFile.length()) + ")");

        try {
            // Resolve each parameter: CLI arg > settings.jsonc > hardcoded default
            File resolvedOutput = outputFile != null ? outputFile
                    : new File(settings.getString("output.file", "output.pdf"));
            File resolvedTxtOutput = txtOutput != null ? txtOutput
                    : new File(resolvedOutput.getAbsolutePath().replaceAll("\\.pdf$", "") + ".txt");
            File resolvedJsonOutput = bboxOutput != null ? bboxOutput
                    : new File(resolvedOutput.getAbsolutePath().replaceAll("\\.pdf$", "") + ".json");
            String resolvedTessdata = tessdataDir != null ? tessdataDir.getAbsolutePath()
                    : settings.getString("tessdata.dir", "./deps/tesseract/tessdata");
            String resolvedNative = nativeDir != null ? nativeDir.getAbsolutePath()
                    : settings.getString("native.dir", "./deps/jbig2enc");

            System.out.println("  Output: " + resolvedOutput);

            // Create pipeline components with configured values
            float resolvedDpi = dpi != null ? dpi
                    : (float) settings.getDouble("rendering.dpi", 300);
            String resolvedLang = language != null ? language
                    : settings.getString("tesseract.language", "eng");
            String resolvedPsm = psm != null ? psm
                    : settings.getString("tesseract.psm", "1");

            boolean useMrc = !noMrc && settings.getBoolean("pipeline.mrc.enabled", true);
            boolean usePdfa = pdfa != null ? pdfa : settings.getBoolean("pdf.pdfa.enabled", false);
            System.out.println("  DPI:    " + resolvedDpi);
            System.out.println("  PSM:    " + resolvedPsm);
            System.out.println("  MRC:    " + (useMrc ? "on" : "off"));
            ImageSegmenter segmenter = new ImageSegmenter(
                    settings.getInt("segmenter.tileSize", 64),
                    settings.getDouble("segmenter.percentile", 0.95),
                    settings.getInt("segmenter.inpaintRadius", 3)
            );
            OCREngine ocrEngine = new OCREngine(
                    resolvedTessdata,
                    tesseractPath != null ? tesseractPath : settings.getString("tesseract.path", "./deps/tesseract/linux/tesseract"),
                    resolvedLang,
                    resolvedPsm
            );
            JBIG2Compressor compressor = new JBIG2Compressor(resolvedNative);
            PDFAssembler assembler = new PDFAssembler(
                    settings.getString("pdf.font", "HELVETICA"),
                    (float) settings.getDouble("pdf.minFontSize", 1.0)
            );

            if (usePdfa) {
                String fontPath = settings.getString("pdf.pdfa.fontPath", "");
                if (!fontPath.isEmpty()) {
                    assembler.setPdfaFont(new File(fontPath));
                }
            }

            assembler.setCompressor(compressor);
            assembler.setBackgroundScale(settings.getDouble("pipeline.mrc.backgroundScale", 0.33));
            assembler.setBgSmoothSigma((float) settings.getDouble("pipeline.mrc.bgSmoothSigma", 0.8));
            assembler.setProducer(settings.getString("pdf.producer", "TrulyFreeOCR"));

            // Resolve worker thread count: CLI arg > settings cap > default (available processors)
            int workerThreads;
            if (threads != null) {
                workerThreads = threads;
            } else {
                int maxThreads = settings.getInt("pipeline.ocr.maxThreads", 1);
                int avail = Runtime.getRuntime().availableProcessors();
                workerThreads = Math.min(avail, maxThreads);
            }
            System.out.println("  OCR Workers: " + workerThreads + " thread(s)");

            // Run the pipeline
            runPipeline(inputFile, resolvedOutput, resolvedTxtOutput, resolvedJsonOutput, segmenter, ocrEngine, compressor, assembler,
                useMrc, usePdfa, resolvedDpi, workerThreads);
            return 0;

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    private void runPipeline(File inputFile, File outputFile, File txtOutput, File bboxOutput,
                             ImageSegmenter segmenter,
                             OCREngine ocrEngine, JBIG2Compressor compressor,
                             PDFAssembler assembler, boolean useMrc, boolean usePdfa,
                             float dpi, int workerThreads) throws IOException {

        String inputName = inputFile.getName().replaceAll("\\.[^.]+$", "");
        File tempDir = new File("temp/" + inputName + "-" + System.nanoTime());
        tempDir.mkdirs();

        try {
            if (isImageFile(inputFile)) {
                runImagePipeline(inputFile, outputFile, txtOutput, bboxOutput, tempDir, inputName,
                    segmenter, ocrEngine, compressor, assembler,
                    useMrc, usePdfa, dpi, workerThreads);
            } else {
                runPdfPipeline(inputFile, outputFile, txtOutput, bboxOutput, tempDir, inputName,
                    segmenter, ocrEngine, compressor, assembler,
                    useMrc, usePdfa, dpi, workerThreads);
            }
        } finally {
            deleteDir(tempDir);
        }
    }

    private void runPdfPipeline(File inputFile, File outputFile, File txtOutput, File bboxOutput,
                                File tempDir, String inputName,
                                ImageSegmenter segmenter,
                                OCREngine ocrEngine, JBIG2Compressor compressor,
                                PDFAssembler assembler, boolean useMrc, boolean usePdfa,
                                float dpi, int workerThreads) throws IOException {
        try (PDDocument source = Loader.loadPDF(inputFile)) {
            PDFRenderer renderer = new PDFRenderer(source);
            int pageCount = source.getNumberOfPages();
            int srcWords = countWords(source);
            System.out.println("  Pages:  " + pageCount);
            if (srcWords > 0) {
                System.out.println("  Words:  " + srcWords + " (source text)");
            }
            processPages(source, pageCount, srcWords, outputFile, txtOutput, bboxOutput, tempDir,
                segmenter, ocrEngine, compressor, assembler,
                useMrc, usePdfa, dpi, workerThreads,
                (i) -> renderer.renderImageWithDPI(i, dpi));
        }
    }

    private void runImagePipeline(File inputFile, File outputFile, File txtOutput, File bboxOutput,
                                  File tempDir, String inputName,
                                  ImageSegmenter segmenter,
                                  OCREngine ocrEngine, JBIG2Compressor compressor,
                                  PDFAssembler assembler, boolean useMrc, boolean usePdfa,
                                  float dpi, int workerThreads) throws IOException {
        List<BufferedImage> pages = loadImagePages(inputFile);
        int pageCount = pages.size();
        float imageDpi = getImageDPI(inputFile, dpi);
        if (imageDpi > 0) {
            dpi = imageDpi;
        }

        System.out.println("  Pages:  " + pageCount);
        if (pageCount == 0) {
            throw new IOException("No pages found in image file: " + inputFile);
        }

        // Create a synthetic source PDF with blank pages sized for the image dimensions
        try (PDDocument source = new PDDocument()) {
            for (BufferedImage page : pages) {
                float w = page.getWidth() * 72f / dpi;
                float h = page.getHeight() * 72f / dpi;
                source.addPage(new PDPage(new PDRectangle(w, h)));
            }
            processPages(source, pageCount, 0, outputFile, txtOutput, bboxOutput, tempDir,
                segmenter, ocrEngine, compressor, assembler,
                useMrc, usePdfa, dpi, workerThreads,
                pages::get);
        }
    }

    private void processPages(PDDocument source, int pageCount, int srcWords,
                              File outputFile, File txtOutput, File bboxOutput, File tempDir,
                              ImageSegmenter segmenter,
                              OCREngine ocrEngine, JBIG2Compressor compressor,
                              PDFAssembler assembler, boolean useMrc, boolean usePdfa,
                              float dpi, int workerThreads,
                              PageProvider pageProvider) throws IOException {
        if (srcWords > 0) {
            System.out.println("  Words:  " + srcWords + " (source text)");
        }
        long totalStart = System.nanoTime();

        System.out.println("  Processing " + pageCount + " pages...");

        // ── Pass 1: render, prep, OCR ──
        //   Rendering is sequential on the main thread.
        //   Prep (grayscale, segmentation, ImageIO) + OCR run in the worker pool,
        //   bounded by workerThreads.  A Semaphore throttles the main thread to
        //   workerThreads + 2 queued pages to avoid OOM.
        List<PageResult> ocrResults = new ArrayList<>(pageCount);
        int[] imgWidths = new int[pageCount];
        int[] imgHeights = new int[pageCount];
        AtomicInteger threadCounter = new AtomicInteger(1);
        ExecutorService ocrExecutor = Executors.newFixedThreadPool(workerThreads,
            r -> new Thread(r, "ocr-" + threadCounter.getAndIncrement()));
        try {
            List<Future<PageResult>> ocrFutures = new ArrayList<>(pageCount);
            long pipelineStart = System.nanoTime();

            int maxLabelWidth = ("[ocr-" + workerThreads + "]").length();
            String perPageFmt = "    %-" + maxLabelWidth + "s page %d: %4.1fs [+%4.1fs to walltime]%n";

            Semaphore semaphore = new Semaphore(workerThreads + 2);

            for (int i = 0; i < pageCount; i++) {
                try {
                    semaphore.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Pipeline interrupted", e);
                }

                BufferedImage page = pageProvider.getPage(i);

                final int pageIdx = i;
                ocrFutures.add(ocrExecutor.submit(() -> {
                    long localStart = System.nanoTime();
                    try {
                        // Convert to grayscale and save for OCR
                        BufferedImage gray = toGrayscale(page);
                        ImageIO.write(gray, "bmp",
                            new File(tempDir, "page-" + pageIdx + ".bmp"));

                        imgWidths[pageIdx] = gray.getWidth();
                        imgHeights[pageIdx] = gray.getHeight();

                        // Segment
                        BufferedImage background;
                        if (useMrc) {
                            SegmentedImage seg = segmenter.segment(page);
                            background = seg.getCleanedBackground();
                            ImageIO.write(seg.getForegroundMask(), "bmp",
                                new File(tempDir, "mask-" + pageIdx + ".bmp"));
                        } else {
                            background = gray;
                        }
                        ImageIO.write(background, "bmp",
                            new File(tempDir, "bg-" + pageIdx + ".bmp"));

                        // OCR
                        PageResult r = ocrEngine.ocr(pageIdx, tempDir);
                        double elapsed = (System.nanoTime() - localStart) / 1e9;
                        double cumulative = (System.nanoTime() - pipelineStart) / 1e9;
                        String tn = Thread.currentThread().getName();
                        System.out.printf(perPageFmt, "[" + tn + "]", pageIdx + 1,
                            elapsed, cumulative);
                        return r;
                    } finally {
                        semaphore.release();
                    }
                }));
            }

            // Shutdown executor (no more submissions)
            ocrExecutor.shutdown();

            // Collect results in page order
            try {
                for (Future<PageResult> f : ocrFutures) {
                    ocrResults.add(f.get());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Pipeline was interrupted", e);
            } catch (ExecutionException e) {
                throw new IOException("OCR task failed", e.getCause());
            }

            long processingDone = System.nanoTime();
            double processingWall = (processingDone - pipelineStart) / 1e9;
            System.out.printf("    Processing done: %d pages in %.1fs (%d threads)%n",
                pageCount, processingWall, workerThreads);

            // ── Write extracted text ──
            System.out.println("  Writing text output...");
            writeTextOutput(txtOutput, ocrResults);

            // ── Write JSON bounding box output ──
            System.out.println("  Writing JSON output...");
            writeJsonOutput(bboxOutput, ocrResults);

            // ── JBIG2 batch compression (shared dictionary across all pages) ──
            JBIG2Compressor.BatchResult jbig2Batch = null;
            if (useMrc && compressor != null) {
                long t0 = System.nanoTime();
                System.out.println("  Batch JBIG2 compression...");
                jbig2Batch = compressor.compressAllFromDir(tempDir, pageCount, imgWidths, imgHeights);
                double elapsed = (System.nanoTime() - t0) / 1e9;
                if (jbig2Batch != null) {
                    System.out.printf("    JBIG2 batch done in %.1fs (sym: %d bytes)%n",
                        elapsed, jbig2Batch.getGlobalSym().length);
                } else {
                    System.out.println("    JBIG2 unavailable — using CCITT G4 fallback");
                }
            }

            // ── Pass 2: assemble (streaming) ──
            System.out.println("  Assembling PDF...");
            try (PDDocument output = new PDDocument()) {
                List<PDPage> outPages = new ArrayList<>(pageCount);

                for (int i = 0; i < pageCount; i++) {
                    BufferedImage bg = ImageIO.read(new File(tempDir, "bg-" + i + ".bmp"));

                    PDPage outPage;
                    if (jbig2Batch != null && jbig2Batch.getGlobalSym().length > 0) {
                        JBIG2Compressor.CompressionResult pageData = jbig2Batch.getPages().get(i);
                        outPage = assembler.addPageJbig2(output, source, i, bg,
                            pageData.getData(), jbig2Batch.getGlobalSym(),
                            pageData.getWidth(), pageData.getHeight(),
                            ocrResults.get(i));
                    } else if (useMrc) {
                        // CCITT G4 foreground (no shared JBIG2 available)
                        BufferedImage mask = ImageIO.read(new File(tempDir, "mask-" + i + ".bmp"));
                        outPage = assembler.addPage(output, source, i, bg, mask, ocrResults.get(i));
                        mask = null;
                    } else {
                        outPage = assembler.addPage(output, source, i, bg, null, ocrResults.get(i));
                    }

                    outPages.add(outPage);
                    bg = null;
                }

                // Finalize: copy metadata, add PDF/A if needed
                System.out.println("  Finalizing document...");
                assembler.finishAssembly(output, source, outPages, usePdfa);
                outputFile.delete();
                output.save(outputFile);

                long totalElapsed = (System.nanoTime() - totalStart) / 1_000_000_000L;
                System.out.printf("  Total: %d pages in %d:%02d%n", pageCount,
                        totalElapsed / 60, totalElapsed % 60);

                int outWords = countOcrWords(ocrResults);
                System.out.println("  Output: " + outputFile.getName() + " (" + formatSize(outputFile.length()) + ")");
                System.out.println("  Words:  " + outWords + " (OCR)");

                long tEnd = System.nanoTime();
                double asmWall = (tEnd - processingDone) / 1e9;
                double totalDouble = (tEnd - pipelineStart) / 1e9;
                System.out.printf("Done.  prep+ocr %.1fs / asm %.1fs = %.1fs total%n",
                    totalDouble - asmWall, asmWall, totalDouble);
                System.out.println();
            }
        } finally {
            ocrExecutor.shutdownNow();
        }
    }

    private static void writeJsonOutput(File file, List<PageResult> results) throws IOException {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        List<JsonPage> pages = new ArrayList<>(results.size());
        for (PageResult page : results) {
            List<JsonWord> words = new ArrayList<>(page.getTextBlocks().size());
            for (TextBlock tb : page.getTextBlocks()) {
                words.add(new JsonWord(
                    tb.getWord(),
                    tb.getBbox().x,
                    tb.getBbox().y,
                    tb.getBbox().x + tb.getBbox().width,
                    tb.getBbox().y + tb.getBbox().height,
                    tb.getConfidence()
                ));
            }
            pages.add(new JsonPage(page.getPageNumber(), page.getWidth(), page.getHeight(), words));
        }
        Files.writeString(file.toPath(), gson.toJson(pages), StandardCharsets.UTF_8);
    }

    private static class JsonPage {
        int page;
        int width;
        int height;
        List<JsonWord> words;
        JsonPage(int page, int width, int height, List<JsonWord> words) {
            this.page = page; this.width = width; this.height = height; this.words = words;
        }
    }

    private static class JsonWord {
        String word;
        int x1;
        int y1;
        int x2;
        int y2;
        double confidence;
        JsonWord(String word, int x1, int y1, int x2, int y2, double confidence) {
            this.word = word; this.x1 = x1; this.y1 = y1;
            this.x2 = x2; this.y2 = y2; this.confidence = confidence;
        }
    }

    private static void writeTextOutput(File file, List<PageResult> results) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (PageResult page : results) {
            sb.append("page ").append(page.getPageNumber()).append("\n");
            sb.append("======\n");
            for (var block : page.getTextBlocks()) {
                sb.append(block.getWord()).append(" ");
            }
            sb.append("\n\n");
        }
        Files.writeString(file.toPath(), sb.toString(), StandardCharsets.UTF_8);
    }

    private static BufferedImage toGrayscale(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_BYTE_GRAY) return image;
        BufferedImage gray = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = gray.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return gray;
    }

    private static boolean isImageFile(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        return IMAGE_EXTENSIONS.contains(name.substring(dot + 1));
    }

    /**
     * Reads DPI from image metadata (Exif for JPEG, TIFF tags).
     * Falls back to defaultDpi if metadata is unavailable or unreadable.
     */
    private static float getImageDPI(File file, float defaultDpi) {
        try (ImageInputStream iis = ImageIO.createImageInputStream(file)) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) return defaultDpi;
            ImageReader r = readers.next();
            r.setInput(iis);
            float xres = defaultDpi;
            try {
                IIOMetadata meta = r.getImageMetadata(0);
                if (meta != null) {
                    // Try standard "HorizontalPixelSize" in millimeters (Tree model)
                    double hps = -1;
                    String[] names = meta.getMetadataFormatNames();
                    if (names != null) {
                        for (String fmt : names) {
                            Node root = meta.getAsTree(fmt);
                            hps = findHorizontalPixelSize(root);
                            if (hps > 0) break;
                        }
                    }
                    if (hps > 0) {
                        xres = (float) (25.4 / hps);
                    }
                }
            } catch (Exception ignored) {
            }
            return xres > 0 ? xres : defaultDpi;
        } catch (Exception e) {
            return defaultDpi;
        }
    }

    private static double findHorizontalPixelSize(Node node) {
        if (node == null) return -1;
        if ("HorizontalPixelSize".equals(node.getLocalName())) {
            Node first = node.getFirstChild();
            if (first != null) {
                try {
                    return Double.parseDouble(first.getNodeValue());
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        NamedNodeMap attrs = node.getAttributes();
        if (attrs != null) {
            Node attr = attrs.getNamedItem("HorizontalPixelSize");
            if (attr != null) {
                try {
                    return Double.parseDouble(attr.getNodeValue());
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            double val = findHorizontalPixelSize(children.item(i));
            if (val > 0) return val;
        }
        return -1;
    }

    /**
     * Loads image pages from a file. For single-image formats (PNG, JPEG, BMP, GIF)
     * returns a single-page list. For multi-page TIFF returns all pages.
     */
    private static List<BufferedImage> loadImagePages(File file) throws IOException {
        List<BufferedImage> pages = new ArrayList<>();

        String name = file.getName().toLowerCase(Locale.ROOT);
        // Try multi-page reader first (TIFF)
        try (ImageInputStream iis = ImageIO.createImageInputStream(file)) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                throw new IOException("Unsupported image format: " + file);
            }
            ImageReader r = readers.next();
            r.setInput(iis);
            int numPages = r.getNumImages(true);
            for (int i = 0; i < numPages; i++) {
                BufferedImage img = r.read(i);
                if (img == null) continue;
                // Flatten alpha and convert to a standard type
                pages.add(toRgb(img));
            }
        }

        if (pages.isEmpty()) {
            BufferedImage img = ImageIO.read(file);
            if (img == null) {
                throw new IOException("Unable to read image: " + file);
            }
            pages.add(toRgb(img));
        }

        return pages;
    }

    private static BufferedImage toRgb(BufferedImage img) {
        if (img.getType() == BufferedImage.TYPE_INT_RGB
            || img.getType() == BufferedImage.TYPE_3BYTE_BGR
            || img.getType() == BufferedImage.TYPE_BYTE_GRAY) {
            return img;
        }
        BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return rgb;
    }

    @FunctionalInterface
    private interface PageProvider {
        BufferedImage getPage(int index) throws IOException;
    }

    private static void deleteDir(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    deleteDir(f);
                }
                f.delete();
            }
        }
        dir.delete();
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.0f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static int countWords(PDDocument doc) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        String text = stripper.getText(doc).trim();
        if (text.isEmpty()) return 0;
        return text.split("\\s+").length;
    }

    private static int countOcrWords(List<PageResult> results) {
        int count = 0;
        for (PageResult page : results) {
            for (TextBlock tb : page.getTextBlocks()) {
                String w = tb.getWord().trim();
                if (!w.isEmpty()) count++;
            }
        }
        return count;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new TrulyFreeOCR()).execute(args);
        System.exit(exitCode);
    }
}
