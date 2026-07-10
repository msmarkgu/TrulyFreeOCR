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

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.trulyfreeocr.model.PageResult;
import com.trulyfreeocr.model.SegmentedImage;

/**
 * End-to-end integration tests running the full pipeline
 * (PageExtractor → ImageSegmenter → OCREngine → PDFAssembler)
 * on all sample PDFs and verifying the output.
 */
class PipelineIntegrationTest {

    static PageExtractor extractor;
    static ImageSegmenter segmenter;
    static OCREngine engine;
    static PDFAssembler assembler;

    @BeforeAll
    static void setup() {
        extractor = new PageExtractor();
        segmenter = new ImageSegmenter();
        engine = new OCREngine();
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
        File input = new File("tests/test-files/generated/with-annotations.pdf");
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
        File input = new File("tests/test-files/generated/simple-text.pdf");
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
        File input = new File("tests/test-files/generated/simple-text.pdf");
        var pages = extractor.extractPages(input);
        var segmented = pages.stream().map(segmenter::segment).toList();
        var backgrounds = segmented.stream().map(SegmentedImage::getCleanedBackground).toList();
        var foregroundMasks = segmented.stream().map(SegmentedImage::getForegroundMask).toList();
        var ocrResults = processOcr(engine, pages);

        File tempOutput = File.createTempFile("tfocr-integration-", ".pdf");
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

    // ── Helpers ─────────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface OutputValidator {
        void validate(PDDocument output, File sourceFile) throws IOException;
    }

    private void runPipeline(String testPdfName, OutputValidator validator) throws IOException {
        File input = new File("tests/test-files/generated/" + testPdfName);
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

    private List<PageResult> processOcr(OCREngine engine, List<BufferedImage> pages) throws IOException {
        Path tempDir = Files.createTempDirectory("tfocr-test-");
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
            List<PageResult> results = new ArrayList<>(pages.size());
            for (int i = 0; i < pages.size(); i++) {
                results.add(engine.ocr(i, tempDir.toFile()));
            }
            return results;
        } finally {
            File dir = tempDir.toFile();
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) f.delete();
            }
            dir.delete();
        }
    }
}
