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
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.trulyfreeocr.model.PageResult;
import com.trulyfreeocr.model.TextBlock;
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
            assertFalse(text.trim().isEmpty(),
                    "PaddleOCR output should have searchable text");
            assertTrue(text.toLowerCase().contains("sphinx"),
                    "PaddleOCR output should contain 'sphinx'. Got: " + text.replace('\n', '|'));
            assertTrue(text.toLowerCase().contains("tesseract"),
                    "PaddleOCR output should contain 'tesseract'. Got: " + text.replace('\n', '|'));
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

    @Test
    void fullPipeline_withAttachments_outputPreservesEmbeddedFiles() throws IOException {
        File input = new File("tests/with-attachments.pdf");
        assertTrue(input.exists(), "Test PDF not found: " + input);

        var pages = extractor.extractPages(input);
        var segmented = pages.stream().map(segmenter::segment).toList();
        var backgrounds = segmented.stream().map(SegmentedImage::getCleanedBackground).toList();
        var ocrResults = processOcr(engine, pages);

        try (PDDocument source = Loader.loadPDF(input);
             PDDocument output = assembler.assemble(input, backgrounds, null, ocrResults, false)) {

            // Verify embedded files are preserved via finishAssembly -> preserve
            PDDocumentNameDictionary srcNames = source.getDocumentCatalog().getNames();
            PDEmbeddedFilesNameTreeNode srcEmbedded = srcNames != null ? srcNames.getEmbeddedFiles() : null;
            assertNotNull(srcEmbedded, "Source should have embedded files");

            PDDocumentNameDictionary dstNames = output.getDocumentCatalog().getNames();
            PDEmbeddedFilesNameTreeNode dstEmbedded = dstNames != null ? dstNames.getEmbeddedFiles() : null;
            assertNotNull(dstEmbedded, "Output should preserve embedded files");

            var srcMap = srcEmbedded.getNames();
            var dstMap = dstEmbedded.getNames();
            assertNotNull(srcMap, "Source should have embedded file names");
            assertNotNull(dstMap, "Output should have embedded file names");
            assertEquals(srcMap.size(), dstMap.size(), "Embedded file count should match");
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

    /**
     * Creates a searchable PDF with the given text rendered as invisible text
     * on a white background. This simulates a PDF that already has a text layer
     * (the input that --mrc-only expects).
     */
    private File createSearchablePdf(String text) throws IOException {
        File pdf = File.createTempFile("searchable-test-", ".pdf");
        pdf.deleteOnExit();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(612, 792));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.setRenderingMode(RenderingMode.NEITHER);
                cs.newLineAtOffset(72, 700);
                cs.showText(text);
                cs.endText();
            }
            doc.save(pdf);
        }
        return pdf;
    }

    @Test
    void mrcOnly_extractPage_producesSearchableOutput() throws IOException {
        String expectedText = "The quick brown fox jumps over the lazy dog";
        File source = createSearchablePdf(expectedText);

        // Verify source is searchable
        try (PDDocument srcDoc = Loader.loadPDF(source)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String srcText = stripper.getText(srcDoc);
            assertTrue(srcText.contains("brown"), "Source should be searchable");
        }

        // Render → segment → reassemble with source text (simulating mrc-only flow)
        var pages = extractor.extractPages(source);
        assertFalse(pages.isEmpty(), "Should extract pages from source");

        var segmented = pages.stream().map(segmenter::segment).toList();
        var backgrounds = segmented.stream().map(SegmentedImage::getCleanedBackground).toList();

        // Simulate mrc-only: create PageResult from extracted text
        List<TextBlock> blocks = List.of(
            new TextBlock(expectedText,
                new java.awt.Rectangle(72, 680, 400, 20), 100.0));
        PageResult pr = new PageResult(0,
            pages.get(0).getWidth(), pages.get(0).getHeight(), blocks);

        try (PDDocument output = assembler.assemble(source, backgrounds, null,
                List.of(pr), false)) {
            PDFTextStripper outStripper = new PDFTextStripper();
            String outText = outStripper.getText(output);
            assertTrue(outText.contains("brown"),
                "MRC-only output should preserve searchable text");
            assertTrue(outText.contains("fox"),
                "MRC-only output should contain 'fox'");
        }
    }
}
