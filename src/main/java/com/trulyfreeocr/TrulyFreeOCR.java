package com.trulyfreeocr;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.rendering.PDFRenderer;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import com.trulyfreeocr.model.PageResult;
import com.trulyfreeocr.model.SegmentedImage;
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

    @Parameters(index = "0", description = "Input PDF file")
    private File inputFile;

    @Option(names = {"-o", "--output"}, description = "Output PDF file")
    private File outputFile;

    @Option(names = {"--native-dir"}, description = "Directory containing native binaries")
    private File nativeDir;

    @Option(names = {"--tessdata-dir"}, description = "Directory containing Tesseract language data")
    private File tessdataDir;

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

    @Option(names = {"--threads"}, description = "Threads for segmentation (default: # CPUs). OCR limited to pipeline.ocr.maxThreads (default 1).")
    private Integer threads;

    @Option(names = {"--txt-output"}, description = "Path for extracted text output (default: <output>.txt)")
    private File txtOutput;

    @Override
    public Integer call() {
        Settings settings = Settings.load();
        if (settingsFile != null) {
            System.setProperty("tfocr.settings", settingsFile.getAbsolutePath());
            settings = Settings.load();
        }

        System.out.println("TrulyFreeOCR v1.0.0");
        System.out.println("  Input:  " + inputFile);

        try {
            // Resolve each parameter: CLI arg > settings.jsonc > hardcoded default
            File resolvedOutput = outputFile != null ? outputFile
                    : new File(settings.getString("output.file", "output.pdf"));
            File resolvedTxtOutput = txtOutput != null ? txtOutput
                    : new File(resolvedOutput.getAbsolutePath().replaceAll("\\.pdf$", "") + ".txt");
            String resolvedTessdata = tessdataDir != null ? tessdataDir.getAbsolutePath()
                    : settings.getString("tessdata.dir", "./tessdata");
            String resolvedNative = nativeDir != null ? nativeDir.getAbsolutePath()
                    : settings.getString("native.dir", "native");

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
            ImageSegmenter segmenter = new ImageSegmenter(
                    settings.getInt("segmenter.tileSize", 64),
                    settings.getDouble("segmenter.percentile", 0.95),
                    settings.getInt("segmenter.inpaintRadius", 3)
            );
            OCREngine ocrEngine = new OCREngine(
                    resolvedTessdata,
                    settings.getString("tesseract.path", "tesseract"),
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
            runPipeline(inputFile, resolvedOutput, resolvedTxtOutput, segmenter, ocrEngine, compressor, assembler,
                useMrc, usePdfa, resolvedDpi, workerThreads);
            return 0;

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    private void runPipeline(File inputFile, File outputFile, File txtOutput,
                             ImageSegmenter segmenter,
                             OCREngine ocrEngine, JBIG2Compressor compressor,
                             PDFAssembler assembler, boolean useMrc, boolean usePdfa,
                             float dpi, int workerThreads) throws IOException {

        // Create temp directory for this run's intermediate files
        String inputName = inputFile.getName().replaceAll("\\.pdf$", "");
        File tempDir = new File("temp/" + inputName + "-" + System.nanoTime());
        tempDir.mkdirs();

        try (PDDocument source = Loader.loadPDF(inputFile)) {
            PDFRenderer renderer = new PDFRenderer(source);
            int pageCount = source.getNumberOfPages();
            long totalStart = System.nanoTime();

            System.out.println("  Processing " + pageCount + " pages...");

            // ── Pass 1: render, prep, OCR ──
            //   Rendering is sequential on the main thread (PDFRenderer not thread-safe).
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

                    BufferedImage page = renderer.renderImageWithDPI(i, dpi);

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

                    long tEnd = System.nanoTime();
                    double asmWall = (tEnd - processingDone) / 1e9;
                    double totalDouble = (tEnd - pipelineStart) / 1e9;
                    System.out.printf("Done.  prep+ocr %.1fs / asm %.1fs = %.1fs total%n",
                        totalDouble - asmWall, asmWall, totalDouble);
                }
            } finally {
                ocrExecutor.shutdownNow();
            }

        } finally {
            deleteDir(tempDir);
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

    public static void main(String[] args) {
        int exitCode = new CommandLine(new TrulyFreeOCR()).execute(args);
        System.exit(exitCode);
    }
}
