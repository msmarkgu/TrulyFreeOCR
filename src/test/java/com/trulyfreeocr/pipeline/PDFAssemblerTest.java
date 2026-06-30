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
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.trulyfreeocr.model.PageResult;
import com.trulyfreeocr.model.SegmentedImage;

/**
 * Integration tests for PDFAssembler that run the full pipeline
 * (PageExtractor → ImageSegmenter → OCREngine → PDFAssembler).
 *
 * Verifies: output PDF creation, correct page count for multi-page inputs,
 * text extractability (searchable PDF), and correct page dimensions
 * (US Letter = 612×792 pt).
 */
class PDFAssemblerTest {

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

    private static List<PageResult> ocrPages(OCREngine engine, List<BufferedImage> pages) throws IOException {
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

    @Test
    void assemble_simpleText_createsSearchablePdf() throws IOException {
        File input = new File("tests/test-files/simple-text.pdf");
        var pages = extractor.extractPages(input);
        assertFalse(pages.isEmpty());

        // Segment and OCR each page
        var backgrounds = pages.stream()
                .map(segmenter::segment)
                .map(SegmentedImage::getCleanedBackground)
                .toList();
        var ocrResults = ocrPages(engine, pages);

        try (PDDocument output = assembler.assemble(input, backgrounds, null, ocrResults, false)) {
            assertNotNull(output);
            assertEquals(1, output.getNumberOfPages());

            // Verify text is extractable (searchable)
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(output);
            assertTrue(text.toLowerCase().contains("quick"),
                    "Output PDF should contain 'quick' but got: " + text);
        }
    }

    @Test
    void assemble_multiPage_preservesPageCount() throws IOException {
        File input = new File("tests/test-files/multi-page.pdf");
        var pages = extractor.extractPages(input);
        assertEquals(3, pages.size());

        var backgrounds = pages.stream()
                .map(segmenter::segment)
                .map(SegmentedImage::getCleanedBackground)
                .toList();
        var ocrResults = ocrPages(engine, pages);

        try (PDDocument output = assembler.assemble(input, backgrounds, null, ocrResults, false)) {
            assertEquals(3, output.getNumberOfPages());
        }
    }

    @Test
    void assemble_multiPage_eachPageHasText() throws IOException {
        File input = new File("tests/test-files/multi-page.pdf");
        var pages = extractor.extractPages(input);
        var backgrounds = pages.stream()
                .map(segmenter::segment)
                .map(SegmentedImage::getCleanedBackground)
                .toList();
        var ocrResults = ocrPages(engine, pages);

        try (PDDocument output = assembler.assemble(input, backgrounds, null, ocrResults, false)) {
            PDFTextStripper stripper = new PDFTextStripper();
            for (int i = 0; i < 3; i++) {
                stripper.setStartPage(i + 1);
                stripper.setEndPage(i + 1);
                String text = stripper.getText(output);
                assertFalse(text.trim().isEmpty(),
                        "Page " + (i + 1) + " should have extractable text");
            }
        }
    }

    @Test
    void assemble_blankPage_createsPdfWithCorrectDimensions() throws IOException {
        File input = new File("tests/test-files/blank.pdf");
        var pages = extractor.extractPages(input);
        var backgrounds = pages.stream()
                .map(segmenter::segment)
                .map(SegmentedImage::getCleanedBackground)
                .toList();
        var ocrResults = ocrPages(engine, pages);

        try (PDDocument output = assembler.assemble(input, backgrounds, null, ocrResults, false)) {
            assertEquals(1, output.getNumberOfPages());
            var mediaBox = output.getPage(0).getMediaBox();
            assertEquals(612.0f, mediaBox.getWidth(), 0.01);
            assertEquals(792.0f, mediaBox.getHeight(), 0.01);
        }
    }

    @Test
    void assemble_withForegroundMask_usesCcittStencil() throws IOException {
        File input = new File("tests/test-files/simple-text.pdf");
        var pages = extractor.extractPages(input);
        var segmented = pages.stream().map(segmenter::segment).toList();
        var backgrounds = segmented.stream().map(SegmentedImage::getCleanedBackground).toList();
        var foregroundMasks = segmented.stream().map(SegmentedImage::getForegroundMask).toList();
        var ocrResults = ocrPages(engine, pages);

        try (PDDocument output = assembler.assemble(input, backgrounds, foregroundMasks, ocrResults, false)) {
            assertNotNull(output);
            assertEquals(1, output.getNumberOfPages());

            // Verify text is still searchable
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(output);
            assertTrue(text.toLowerCase().contains("quick"),
                    "Output PDF should contain 'quick' but got: " + text);

            // Verify page resources include a CCITT-encoded image (stencil mask)
            PDPage page = output.getPage(0);
            var resources = page.getResources();
            boolean hasStencil = false;
            for (var name : resources.getXObjectNames()) {
                try {
                    var xObj = resources.getXObject(name);
                    if (xObj instanceof PDImageXObject img) {
                        if (img.getCOSObject().getBoolean(COSName.IMAGE_MASK, false)) {
                            hasStencil = true;
                            break;
                        }
                    }
                } catch (IOException e) {
                    // skip
                }
            }
            assertTrue(hasStencil, "Output PDF should contain an ImageMask stencil");
        }
    }
}
