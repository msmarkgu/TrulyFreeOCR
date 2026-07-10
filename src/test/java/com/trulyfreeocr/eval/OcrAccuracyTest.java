package com.trulyfreeocr.eval;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.trulyfreeocr.model.PageResult;
import com.trulyfreeocr.pipeline.OCREngine;
import com.trulyfreeocr.pipeline.PageExtractor;

/**
 * End-to-end OCR accuracy evaluation on the Sherlock Holmes corpus.
 *
 * Tagged "eval" — excluded from the default test run.
 * Run explicitly with:
 *   ./gradlew testEval
 */
@Tag("eval")
class OcrAccuracyTest {

    private static final Path CORPUS_DIR = Path.of("tests/eval-corpus");
    private static final double WER_THRESHOLD = 0.05; // flag pages above 5%

    private static PageExtractor extractor = new PageExtractor();
    private static OCREngine ocrEngine = new OCREngine();

    @Test void eval_010p() throws IOException { runEval("sherlock-holmes-010p", 10); }
    @Test void eval_020p() throws IOException { runEval("sherlock-holmes-020p", 20); }
    @Test void eval_050p() throws IOException { runEval("sherlock-holmes-050p", 50); }
    @Test void eval_100p() throws IOException { runEval("sherlock-holmes-100p", 100); }

    private void runEval(String stem, int expectedPages) throws IOException {
        Path pdfPath = CORPUS_DIR.resolve(stem + ".pdf");
        Path jsonPath = CORPUS_DIR.resolve(stem + ".json");

        if (!pdfPath.toFile().exists()) {
            System.out.println("SKIP: " + pdfPath + " not found (run generate_eval_corpus.py first)");
            return;
        }

        System.out.println("\n=== " + stem + ".pdf (" + expectedPages + " pages) ===");
        GroundTruth gt = GroundTruth.load(jsonPath);
        evaluateDocument(pdfPath.toFile(), gt);
    }

    private void evaluateDocument(File pdf, GroundTruth gt) throws IOException {
        List<BufferedImage> pages;
        try (PageExtractor pe = extractor) {
            pages = pe.extractPages(pdf);
        }

        int n = Math.min(pages.size(), gt.getPageCount());
        List<PageResult> results = new ArrayList<>(n);

        Path tempDir = Files.createTempDirectory("tfocr-eval-");
        long totalStart = System.currentTimeMillis();

        try {
            for (int i = 0; i < pages.size(); i++) {
                BufferedImage page = pages.get(i);
                if (page.getType() != BufferedImage.TYPE_BYTE_GRAY) {
                    BufferedImage gray = new BufferedImage(page.getWidth(), page.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
                    Graphics2D g = gray.createGraphics();
                    g.drawImage(page, 0, 0, null);
                    g.dispose();
                    page = gray;
                }
                ImageIO.write(page, "bmp", tempDir.resolve("page-" + i + ".bmp").toFile());
            }

            for (int i = 0; i < n; i++) {
                long start = System.currentTimeMillis();
                PageResult result = ocrEngine.ocr(i, tempDir.toFile());
            long elapsed = System.currentTimeMillis() - start;
            results.add(result);

            List<String> gtWords = normalize(gt.getWords(i));
            List<String> ocrWords = normalize(result.getTextBlocks().stream()
                    .map(tb -> tb.getWord()).collect(Collectors.toList()));

            double pageWer = Levenshtein.wer(ocrWords, gtWords);
            String gtText = String.join(" ", gtWords);
            String ocrText = String.join(" ", ocrWords);
            double pageCer = Levenshtein.cer(ocrText, gtText);
            double recall = computeRecall(ocrWords, gtWords);
            double precision = computePrecision(ocrWords, gtWords);
            double meanConf = result.getTextBlocks().stream()
                    .mapToDouble(tb -> tb.getConfidence()).average().orElse(0);

            String flag = pageWer > WER_THRESHOLD ? " ***" : "";
            System.out.printf("  Page %3d: WER=%.1f%%  CER=%.1f%%  recall=%.1f%%  prec=%.1f%%  conf=%.1f  time=%.2fs%s%n",
                    i + 1, pageWer * 100, pageCer * 100, recall * 100, precision * 100,
                    meanConf, elapsed / 1000.0, flag);
        }

        long totalTime = System.currentTimeMillis() - totalStart;

        // Aggregate metrics
        List<String> allGt = new ArrayList<>();
        List<String> allOcr = new ArrayList<>();
        int totalGtWords = 0, totalOcrWords = 0;
        double sumConf = 0;
        int confCount = 0;
        int flaggedPages = 0;

        for (int i = 0; i < n; i++) {
            List<String> gw = normalize(gt.getWords(i));
            List<String> ow = normalize(results.get(i).getTextBlocks().stream()
                    .map(tb -> tb.getWord()).collect(Collectors.toList()));
            allGt.addAll(gw);
            allOcr.addAll(ow);
            totalGtWords += gw.size();
            totalOcrWords += ow.size();
            for (var tb : results.get(i).getTextBlocks()) {
                sumConf += tb.getConfidence();
                confCount++;
            }

            double pageWer = Levenshtein.wer(ow, gw);
            if (pageWer > WER_THRESHOLD) flaggedPages++;
        }

        String gtFull = String.join(" ", allGt);
        String ocrFull = String.join(" ", allOcr);

        double aggregateWer = Levenshtein.wer(allOcr, allGt);
        double aggregateCer = Levenshtein.cer(ocrFull, gtFull);
        double aggregateRecall = computeRecall(allOcr, allGt);
        double aggregatePrecision = computePrecision(allOcr, allGt);
        double meanConf = confCount > 0 ? sumConf / confCount : 0;

        if (flaggedPages > 0) {
            System.out.println("\n  Pages with WER > " + (WER_THRESHOLD * 100) + "%: " + flaggedPages + " / " + n);
        } else {
            System.out.println("\n  Pages with WER > " + (WER_THRESHOLD * 100) + "%: none");
        }

        System.out.println("\n  Document summary:");
        System.out.printf("    Pages:                %d%n", n);
        System.out.printf("    Total GT words:       %d%n", totalGtWords);
        System.out.printf("    Total OCR words:      %d%n", totalOcrWords);
        System.out.printf("    Aggregate WER:        %.1f%%%n", aggregateWer * 100);
        System.out.printf("    Aggregate CER:        %.1f%%%n", aggregateCer * 100);
        System.out.printf("    Word recall:          %.1f%%%n", aggregateRecall * 100);
        System.out.printf("    Word precision:       %.1f%%%n", aggregatePrecision * 100);
        System.out.printf("    Mean confidence:      %.1f%n", meanConf);
        System.out.printf("    Total time:           %.1fs%n", totalTime / 1000.0);
        System.out.printf("    Time per page:        %.2fs%n", totalTime / 1000.0 / n);
        } finally {
            File dir = tempDir.toFile();
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) f.delete();
            }
            dir.delete();
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private List<String> normalize(List<String> words) {
        return words.stream()
                .map(w -> w.replaceAll("^[^\\p{L}\\p{N}]+", ""))
                .map(w -> w.replaceAll("[^\\p{L}\\p{N}]+$", ""))
                .map(String::toLowerCase)
                .filter(w -> !w.isEmpty())
                .collect(Collectors.toList());
    }

    private double computeRecall(List<String> ocrWords, List<String> gtWords) {
        if (gtWords.isEmpty()) return 1.0;
        long found = gtWords.stream().filter(w -> ocrWords.contains(w)).count();
        return (double) found / gtWords.size();
    }

    private double computePrecision(List<String> ocrWords, List<String> gtWords) {
        if (ocrWords.isEmpty()) return 1.0;
        long found = ocrWords.stream().filter(w -> gtWords.contains(w)).count();
        return (double) found / ocrWords.size();
    }
}
