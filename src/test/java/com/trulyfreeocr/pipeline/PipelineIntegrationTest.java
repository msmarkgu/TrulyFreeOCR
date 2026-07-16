package com.trulyfreeocr.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.trulyfreeocr.model.PageResult;
import com.trulyfreeocr.model.SegmentedImage;
import com.trulyfreeocr.pipeline.PaddleOcrOnnxProvider;

/**
 * End-to-end integration tests running the full pipeline
 * (PageExtractor → ImageSegmenter → TesseractProvider → PDFAssembler)
 * on all sample PDFs and verifying the output.
 */
class PipelineIntegrationTest {

    PageExtractor extractor;
    ImageSegmenter segmenter;
    TesseractProvider engine;
    PDFAssembler assembler;

    @BeforeEach
    void setup() {
        extractor = new PageExtractor();
        segmenter = new ImageSegmenter();
        engine = new TesseractProvider();
        assembler = new PDFAssembler();
    }

    @Test
    void fullPipeline_simpleText_outputIsSearchable() throws IOException {
        runPipeline("simple-text.pdf", (output, sourceFile) -> {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(output);
            assertTrue(text.toLowerCase().contains("brown"),
                    "Output should contain 'brown' from the sample text");
            assertTrue(text.toLowerCase().contains("brown"),
                    "Output should contain 'brown' from the sample text");
            assertTrue(text.toLowerCase().contains("fox"),
                    "Output should contain 'fox' from the sample text");
        });
    }

    @Test
    void fullPipeline_blankPage_outputHasNoSearchableText() throws IOException {
        runPipeline("blank.pdf", (output, sourceFile) -> {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(output);
            assertTrue(text.trim().isEmpty(),
                    "Blank page should have no searchable text, but got: '" + text + "'");
        });
    }

    @Test
    void fullPipeline_multiPage_preservesPageCount() throws IOException {
        runPipeline("multi-page.pdf", (output, sourceFile) -> {
            assertEquals(3, output.getNumberOfPages(),
                    "Three-page input should produce three-page output");
        });
    }

    @Test
    void fullPipeline_multiPage_eachPageHasText() throws IOException {
        runPipeline("multi-page.pdf", (output, sourceFile) -> {
            PDFTextStripper stripper = new PDFTextStripper();
            for (int i = 0; i < 3; i++) {
                stripper.setStartPage(i + 1);
                stripper.setEndPage(i + 1);
                String text = stripper.getText(output);
                assertFalse(text.trim().isEmpty(),
                        "Page " + (i + 1) + " should have extractable text");
            }
        });
    }

    @Test
    void fullPipeline_withAnnotations_preservesMetadata() throws IOException {
        File input = new File("tests/with-annotations.pdf");
        var pages = extractor.extractPages(input);
        var segmented = pages.stream().map(segmenter::segment).toList();
        var backgrounds = segmented.stream().map(SegmentedImage::getCleanedBackground).toList();
        var foregroundMasks = segmented.stream().map(SegmentedImage::getForegroundMask).toList();
        var ocrResults = processOcr(engine, pages);

        try (PDDocument source = Loader.loadPDF(input);
             PDDocument output = assembler.assemble(input, backgrounds, foregroundMasks, ocrResults, false)) {

            // Verify document info preserved
            var srcInfo = source.getDocumentInformation();
            var dstInfo = output.getDocumentInformation();
            assertEquals(srcInfo.getTitle(), dstInfo.getTitle());
            assertEquals(srcInfo.getAuthor(), dstInfo.getAuthor());

            // Verify outline preserved if source has one
            var srcOutline = source.getDocumentCatalog().getDocumentOutline();
            if (srcOutline != null) {
                var dstOutline = output.getDocumentCatalog().getDocumentOutline();
                assertNotNull(dstOutline);
            }
        }
    }

    @Test
    void fullPipeline_twoColumn_outputIsSearchable() throws IOException {
        runPipeline("two-column.pdf", (output, sourceFile) -> {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(output);
            assertFalse(text.trim().isEmpty(), "Two-column PDF should have searchable text");
            // Should contain content from both columns
            assertTrue(text.toLowerCase().contains("lorem") || text.toLowerCase().contains("ipsum"),
                    "Two-column output should contain sample text");
        });
    }

    @Test
    void fullPipeline_simpleText_withForegroundMask() throws IOException {
        File input = new File("tests/simple-text.pdf");
        var pages = extractor.extractPages(input);
        var segmented = pages.stream().map(segmenter::segment).toList();
        var backgrounds = segmented.stream().map(SegmentedImage::getCleanedBackground).toList();
        var foregroundMasks = segmented.stream().map(SegmentedImage::getForegroundMask).toList();
        var ocrResults = processOcr(engine, pages);

        try (PDDocument output = assembler.assemble(input, backgrounds, foregroundMasks, ocrResults, false)) {
            assertNotNull(output);
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(output);
            assertTrue(text.toLowerCase().contains("brown"));
        }
    }

    @Test
    void fullPipeline_outputFileSizeIsReasonable() throws IOException {
        File input = new File("tests/simple-text.pdf");
        var pages = extractor.extractPages(input);
        var segmented = pages.stream().map(segmenter::segment).toList();
        var backgrounds = segmented.stream().map(SegmentedImage::getCleanedBackground).toList();
        var foregroundMasks = segmented.stream().map(SegmentedImage::getForegroundMask).toList();
        var ocrResults = processOcr(engine, pages);

        Files.createDirectories(Path.of("temp"));
        File tempOutput = Files.createTempFile(Path.of("temp"), "tfocr-integration-", ".pdf").toFile();
        tempOutput.deleteOnExit();
        try (PDDocument output = assembler.assemble(input, backgrounds, foregroundMasks, ocrResults, false)) {
            output.save(tempOutput);
        }
        // Output should be reasonably sized (not trivially tiny, not enormous)
        long size = tempOutput.length();
        assertTrue(size > 1000, "Output PDF should be at least 1KB, was " + size + " bytes");
        // At 300 DPI for US Letter, even compressed, expect > 10KB
        assertTrue(size < 100_000_000, "Output PDF should be under 100MB, was " + size + " bytes");
    }

    // ── PaddleOCR Integration Tests ─────────────────────────────────────────

    @Test
    void paddleOcr_simpleText_outputIsSearchable() throws IOException {
        // Skip if PaddleOCR models not installed
        if (!new File("deps/paddleocr/det.onnx").exists()) {
            return;
        }
        PaddleOcrOnnxProvider paddle = new PaddleOcrOnnxProvider();

        File input = new File("tests/simple-text.pdf");
        var pages = extractor.extractPages(input);
        var ocrResults = processOcr(paddle, pages);

        try (PDDocument output = assembler.assemble(input,
                pages.stream().map(p -> toGray(p)).toList(),
                null, ocrResults, false)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(output);
            assertTrue(text.toLowerCase().contains("brown"),
                    "PaddleOCR output should contain 'brown'");
            assertTrue(text.toLowerCase().contains("fox"),
                    "PaddleOCR output should contain 'fox'");
        }
    }

    @Test
    void paddleOcr_multiPage_preservesPageCount() throws IOException {
        if (!new File("deps/paddleocr/det.onnx").exists()) {
            return;
        }
        PaddleOcrOnnxProvider paddle = new PaddleOcrOnnxProvider();

        File input = new File("tests/multi-page.pdf");
        var pages = extractor.extractPages(input);
        assertEquals(3, pages.size());

        var ocrResults = processOcr(paddle, pages);
        assertEquals(3, ocrResults.size());
    }

    @Test
    void paddleOcr_twoColumn_outputIsSearchable() throws IOException {
        if (!new File("deps/paddleocr/det.onnx").exists()) {
            return;
        }
        PaddleOcrOnnxProvider paddle = new PaddleOcrOnnxProvider();

        File input = new File("tests/two-column.pdf");
        var pages = extractor.extractPages(input);
        var ocrResults = processOcr(paddle, pages);

        try (PDDocument output = assembler.assemble(input,
                pages.stream().map(p -> toGray(p)).toList(),
                null, ocrResults, false)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(output);
            assertFalse(text.trim().isEmpty(),
                    "PaddleOCR two-column output should have searchable text");
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface OutputValidator {
        void validate(PDDocument output, File sourceFile) throws IOException;
    }

    private void runPipeline(String testPdfName, OutputValidator validator) throws IOException {
        File input = new File("tests/" + testPdfName);
        assertTrue(input.exists(), "Test PDF not found: " + input);

        var pages = extractor.extractPages(input);
        assertNotNull(pages);
        assertFalse(pages.isEmpty());

        var segmented = pages.stream().map(segmenter::segment).toList();
        var backgrounds = segmented.stream().map(SegmentedImage::getCleanedBackground).toList();
        var ocrResults = processOcr(engine, pages);

        try (PDDocument output = assembler.assemble(input, backgrounds, null, ocrResults, false)) {
            assertNotNull(output);
            validator.validate(output, input);
        }
    }

    private List<PageResult> processOcr(OcrProvider engine, List<BufferedImage> pages) throws IOException {
        List<PageResult> results = new ArrayList<>(pages.size());
        for (int i = 0; i < pages.size(); i++) {
            BufferedImage page = pages.get(i);
            BufferedImage gray = page.getType() == BufferedImage.TYPE_BYTE_GRAY ? page
                    : toGray(page);
            results.add(engine.ocr(gray, i));
        }
        return results;
    }

    private static BufferedImage toGray(BufferedImage img) {
        BufferedImage gray = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = gray.createGraphics();
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return gray;
    }
}
