package com.trulyfreeocr.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for PageExtractor using the generated test PDFs in tests/test-files/.
 *
 * Verifies page count, image dimensions (US Letter at 300 DPI ≈ 2550×3300),
 * image type (TYPE_INT_RGB), and resource cleanup via AutoCloseable.
 */
class PageExtractorTest {

    @Test
    void extractPages_blankPdf_returnsOnePage() throws IOException {
        var pdf = new File("tests/test-files/blank.pdf");
        var extractor = new PageExtractor();
        List<BufferedImage> pages = extractor.extractPages(pdf);
        assertEquals(1, pages.size());
    }

    @Test
    void extractPages_blankPdf_returnsNonNullImages() throws IOException {
        var pdf = new File("tests/test-files/blank.pdf");
        var extractor = new PageExtractor();
        List<BufferedImage> pages = extractor.extractPages(pdf);
        assertNotNull(pages.get(0));
    }

    @Test
    void extractPages_blankPdf_returnsCorrectDimensions() throws IOException {
        var pdf = new File("tests/test-files/blank.pdf");
        var extractor = new PageExtractor();
        List<BufferedImage> pages = extractor.extractPages(pdf);
        // US Letter at 300 DPI = 2550x3300
        // US Letter at 300 DPI ~= 2550x3300 (may be off by 1 due to rounding)
        assertEquals(2550, pages.get(0).getWidth());
        assertTrue(Math.abs(pages.get(0).getHeight() - 3300) <= 1);
    }

    @Test
    void extractPages_multiPagePdf_returnsCorrectPageCount() throws IOException {
        var pdf = new File("tests/test-files/multi-page.pdf");
        var extractor = new PageExtractor();
        List<BufferedImage> pages = extractor.extractPages(pdf);
        assertEquals(3, pages.size());
    }

    @Test
    void extractPages_invalidPath_throwsIOException() {
        var pdf = new File("tests/test-files/nonexistent.pdf");
        var extractor = new PageExtractor();
        assertThrows(IOException.class, () -> extractor.extractPages(pdf));
    }

    @Test
    void close_releasesResources() throws IOException {
        var pdf = new File("tests/test-files/simple-text.pdf");
        var extractor = new PageExtractor();
        extractor.extractPages(pdf);
        assertDoesNotThrow(() -> extractor.close());
    }

    @Test
    void extractPages_simpleTextPdf_returns300DpiQuality() throws IOException {
        var pdf = new File("tests/test-files/simple-text.pdf");
        var extractor = new PageExtractor();
        List<BufferedImage> pages = extractor.extractPages(pdf);
        BufferedImage img = pages.get(0);
        assertTrue(Math.abs(img.getWidth() - 2550) <= 1);
        assertTrue(Math.abs(img.getHeight() - 3300) <= 1);
        assertEquals(BufferedImage.TYPE_INT_RGB, img.getType());
    }
}
