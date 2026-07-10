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
import org.junit.jupiter.api.BeforeEach;
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

    PageExtractor extractor;
    ImageSegmenter segmenter;
    OCREngine engine;
    PDFAssembler assembler;

    @BeforeEach
    void setup() {
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
        File input = new File("tests/test-files/generated/simple-text.pdf");
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
            assertTrue(text.toLowerCase().contains("brown"),
                    "Output PDF should contain 'brown' but got: " + text);
        }
    }

    @Test
    void assemble_multiPage_preservesPageCount() throws IOException {
        File input = new File("tests/test-files/generated/multi-page.pdf");
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
        File input = new File("tests/test-files/generated/multi-page.pdf");
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
        File input = new File("tests/test-files/generated/blank.pdf");
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
        File input = new File("tests/test-files/generated/simple-text.pdf");
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
            assertTrue(text.toLowerCase().contains("brown"),
                    "Output PDF should contain 'brown' but got: " + text);

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

    // --- Bug #6: gaussianBlur unit tests ---

    @Test
    void gaussianBlur_noop_whenSigmaZero() {
        BufferedImage img = new BufferedImage(10, 10, BufferedImage.TYPE_3BYTE_BGR);
        assertSame(img, PDFAssembler.gaussianBlur(img, 0f));
    }

    @Test
    void gaussianBlur_preservesDimensions() {
        BufferedImage img = new BufferedImage(7, 13, BufferedImage.TYPE_3BYTE_BGR);
        BufferedImage result = PDFAssembler.gaussianBlur(img, 1.5f);
        assertEquals(7, result.getWidth());
        assertEquals(13, result.getHeight());
    }

    @Test
    void gaussianBlur_smoothensSharpEdge() {
        int w = 20, h = 20;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = img.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setColor(java.awt.Color.BLACK);
        g.fillRect(0, 0, w / 2, h);
        g.dispose();

        BufferedImage blurred = PDFAssembler.gaussianBlur(img, 2f);

        // At the boundary (x=10), pixel should be intermediate gray, not pure black/white
        int boundaryPixel = blurred.getRGB(10, 10);
        int r = (boundaryPixel >> 16) & 0xFF;
        int b = boundaryPixel & 0xFF;
        assertTrue(r > 0 && r < 255,
                "Boundary pixel should be gray (interpolated), got r=" + r);
        assertTrue(b > 0 && b < 255,
                "Boundary pixel should be gray (interpolated), got b=" + b);
    }

    @Test
    void gaussianBlur_handlesTinyImage() {
        BufferedImage img1 = new BufferedImage(1, 1, BufferedImage.TYPE_3BYTE_BGR);
        assertNotNull(PDFAssembler.gaussianBlur(img1, 2f));
        assertEquals(1, img1.getWidth());
        assertEquals(1, img1.getHeight());

        BufferedImage img2 = new BufferedImage(2, 2, BufferedImage.TYPE_3BYTE_BGR);
        assertNotNull(PDFAssembler.gaussianBlur(img2, 2f));
        assertEquals(2, img2.getWidth());
        assertEquals(2, img2.getHeight());
    }

    @Test
    void gaussianBlur_handlesLargeRadius() {
        BufferedImage img = new BufferedImage(5, 5, BufferedImage.TYPE_3BYTE_BGR);
        assertNotNull(PDFAssembler.gaussianBlur(img, 10f));
    }

    // --- Bug #5: MRC background encoding integration tests ---

    @Test
    void assemble_withBackgroundScalingAndBlur_producesValidPdf() throws IOException {
        File input = new File("tests/test-files/generated/simple-text.pdf");
        var pages = extractor.extractPages(input);
        var segmented = pages.stream().map(segmenter::segment).toList();
        var backgrounds = segmented.stream().map(SegmentedImage::getCleanedBackground).toList();
        var foregroundMasks = segmented.stream().map(SegmentedImage::getForegroundMask).toList();
        var ocrResults = ocrPages(engine, pages);

        assembler.setBackgroundScale(0.5);
        assembler.setBgSmoothSigma(2f);
        try (PDDocument output = assembler.assemble(input, backgrounds, foregroundMasks, ocrResults, false)) {
            assertNotNull(output);
            assertEquals(1, output.getNumberOfPages());

            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(output);
            assertTrue(text.toLowerCase().contains("brown"),
                    "Output PDF should contain 'brown' but got: " + text);
        }
    }

    @Test
    void assemble_withBackgroundBlurOnly_producesValidPdf() throws IOException {
        File input = new File("tests/test-files/generated/simple-text.pdf");
        var pages = extractor.extractPages(input);
        var segmented = pages.stream().map(segmenter::segment).toList();
        var backgrounds = segmented.stream().map(SegmentedImage::getCleanedBackground).toList();
        var foregroundMasks = segmented.stream().map(SegmentedImage::getForegroundMask).toList();
        var ocrResults = ocrPages(engine, pages);

        assembler.setBackgroundScale(1.0);
        assembler.setBgSmoothSigma(3f);
        try (PDDocument output = assembler.assemble(input, backgrounds, foregroundMasks, ocrResults, false)) {
            assertNotNull(output);
            assertEquals(1, output.getNumberOfPages());

            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(output);
            assertTrue(text.toLowerCase().contains("brown"),
                    "Output PDF should contain 'brown' but got: " + text);
        }
    }

    @Test
    void assemble_withBackgroundScaleOnly_producesValidPdf() throws IOException {
        File input = new File("tests/test-files/generated/simple-text.pdf");
        var pages = extractor.extractPages(input);
        var segmented = pages.stream().map(segmenter::segment).toList();
        var backgrounds = segmented.stream().map(SegmentedImage::getCleanedBackground).toList();
        var foregroundMasks = segmented.stream().map(SegmentedImage::getForegroundMask).toList();
        var ocrResults = ocrPages(engine, pages);

        assembler.setBackgroundScale(0.33);
        assembler.setBgSmoothSigma(0f);
        try (PDDocument output = assembler.assemble(input, backgrounds, foregroundMasks, ocrResults, false)) {
            assertNotNull(output);
            assertEquals(1, output.getNumberOfPages());

            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(output);
            assertTrue(text.toLowerCase().contains("brown"),
                    "Output PDF should contain 'brown' but got: " + text);
        }
    }
}
