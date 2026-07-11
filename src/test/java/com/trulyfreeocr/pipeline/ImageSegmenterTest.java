package com.trulyfreeocr.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.trulyfreeocr.model.SegmentedImage;

    /**
     * Tests for ImageSegmenter (pure-Java binarization pipeline).
     *
     * Verifies that segment() returns non-null outputs, preserves dimensions,
     * produces a foreground mask, correctly identifies text vs blank pages,
     * and inpaints foreground pixels in the cleaned background.
     */
class ImageSegmenterTest {

    static PageExtractor extractor;
    static ImageSegmenter segmenter;

    @BeforeAll
    static void setup() {
        extractor = new PageExtractor();
        segmenter = new ImageSegmenter();
    }

    @Test
    void segment_blankPage_returnsNonNull() throws IOException {
        var pages = extractor.extractPages(new File("tests/blank.pdf"));
        SegmentedImage result = segmenter.segment(pages.get(0));
        assertNotNull(result);
        assertNotNull(result.getForegroundMask());
        assertNotNull(result.getCleanedBackground());
    }

    @Test
    void segment_preservesDimensions() throws IOException {
        var pages = extractor.extractPages(new File("tests/simple-text.pdf"));
        BufferedImage page = pages.get(0);
        SegmentedImage result = segmenter.segment(page);
        assertEquals(page.getWidth(), result.getForegroundMask().getWidth());
        assertEquals(page.getHeight(), result.getForegroundMask().getHeight());
        assertEquals(page.getWidth(), result.getCleanedBackground().getWidth());
        assertEquals(page.getHeight(), result.getCleanedBackground().getHeight());
    }

    @Test
    void segment_foregroundMaskIsBinary() throws IOException {
        var pages = extractor.extractPages(new File("tests/simple-text.pdf"));
        SegmentedImage result = segmenter.segment(pages.get(0));
        BufferedImage mask = result.getForegroundMask();
        assertEquals(BufferedImage.TYPE_BYTE_BINARY, mask.getType());
    }

    @Test
    void segment_blankPage_hasLittleForeground() throws IOException {
        var pages = extractor.extractPages(new File("tests/blank.pdf"));
        SegmentedImage result = segmenter.segment(pages.get(0));
        BufferedImage mask = result.getForegroundMask();
        int width = mask.getWidth();
        int height = mask.getHeight();
        int blackPixels = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if ((mask.getRGB(x, y) & 0xFFFFFF) == 0) {
                    blackPixels++;
                }
            }
        }
        int total = width * height;
        assertTrue((double) blackPixels / total < 0.05,
                "Blank page should have <5% foreground pixels, got " +
                (100.0 * blackPixels / total) + "%");
    }

    @Test
    void segment_simpleText_hasForegroundPixels() throws IOException {
        var pages = extractor.extractPages(new File("tests/simple-text.pdf"));
        SegmentedImage result = segmenter.segment(pages.get(0));
        BufferedImage mask = result.getForegroundMask();
        int width = mask.getWidth();
        int height = mask.getHeight();
        int blackPixels = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if ((mask.getRGB(x, y) & 0xFFFFFF) == 0) {
                    blackPixels++;
                }
            }
        }
        int total = width * height;
        assertTrue((double) blackPixels / total > 0.001,
                "Text page should have >0.1% foreground pixels, got " +
                (100.0 * blackPixels / total) + "%");
    }

    @Test
    void segment_cleanedBackground_reducesTextNoise() throws IOException {
        var pages = extractor.extractPages(new File("tests/simple-text.pdf"));
        BufferedImage page = pages.get(0);
        SegmentedImage result = segmenter.segment(page);
        BufferedImage cleaned = result.getCleanedBackground();

        // Compare cleaned vs original: text areas should be filled in
        BufferedImage mask = result.getForegroundMask();
        int width = page.getWidth();
        int height = page.getHeight();
        int textPixels = 0;
        int differentPixels = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if ((mask.getRGB(x, y) & 0xFFFFFF) == 0) {
                    textPixels++;
                    int origRgb = page.getRGB(x, y);
                    int cleanRgb = cleaned.getRGB(x, y);
                    if (origRgb != cleanRgb) {
                        differentPixels++;
                    }
                }
            }
        }
        // Most foreground pixels should differ (text filled in)
        assertTrue(textPixels > 0);
        assertTrue((double) differentPixels / textPixels > 0.5,
                "Most foreground pixels should differ between original and cleaned");
    }
}
