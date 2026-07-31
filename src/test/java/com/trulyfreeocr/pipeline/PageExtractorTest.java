package com.trulyfreeocr.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for PageExtractor using the generated test PDFs in tests/.
 *
 * Verifies page count, image dimensions (US Letter at 150 DPI ≈ 1275×1650),
 * image type (TYPE_INT_RGB), and resource cleanup via AutoCloseable.
 */
class PageExtractorTest {

    @Test
    void extractPages_blankPdf_returnsOnePage() throws IOException {
        var pdf = new File("tests/blank.pdf");
        var extractor = new PageExtractor(150f);
        List<BufferedImage> pages = extractor.extractPages(pdf);
        assertEquals(1, pages.size());
    }

    @Test
    void extractPages_blankPdf_returnsNonNullImages() throws IOException {
        var pdf = new File("tests/blank.pdf");
        var extractor = new PageExtractor(150f);
        List<BufferedImage> pages = extractor.extractPages(pdf);
        assertNotNull(pages.get(0));
    }

    @Test
    void extractPages_blankPdf_returnsCorrectDimensions() throws IOException {
        var pdf = new File("tests/blank.pdf");
        var extractor = new PageExtractor(150f);
        List<BufferedImage> pages = extractor.extractPages(pdf);
        // US Letter at 150 DPI = 1275x1650 (may be off by 1 due to rounding)
        assertEquals(1275, pages.get(0).getWidth());
        assertTrue(Math.abs(pages.get(0).getHeight() - 1650) <= 1);
    }

    @Test
    void extractPages_multiPagePdf_returnsCorrectPageCount() throws IOException {
        var pdf = new File("tests/multi-page.pdf");
        var extractor = new PageExtractor(150f);
        List<BufferedImage> pages = extractor.extractPages(pdf);
        assertEquals(3, pages.size());
    }

    @Test
    void extractPages_invalidPath_throwsIOException() {
        var pdf = new File("tests/nonexistent.pdf");
        var extractor = new PageExtractor(150f);
        assertThrows(IOException.class, () -> extractor.extractPages(pdf));
    }

    @Test
    void close_releasesResources() throws IOException {
        var pdf = new File("tests/simple-text.pdf");
        var extractor = new PageExtractor(150f);
        extractor.extractPages(pdf);
        assertDoesNotThrow(() -> extractor.close());
    }

    @Test
    void extractPages_simpleTextPdf_returns150DpiQuality() throws IOException {
        var pdf = new File("tests/simple-text.pdf");
        var extractor = new PageExtractor(150f);
        List<BufferedImage> pages = extractor.extractPages(pdf);
        BufferedImage img = pages.get(0);
        assertTrue(Math.abs(img.getWidth() - 1275) <= 1);
        assertTrue(Math.abs(img.getHeight() - 1650) <= 1);
        assertEquals(BufferedImage.TYPE_INT_RGB, img.getType());
    }
}
