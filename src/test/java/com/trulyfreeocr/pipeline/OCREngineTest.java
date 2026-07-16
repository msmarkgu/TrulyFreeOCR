package com.trulyfreeocr.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.trulyfreeocr.model.PageResult;

class OCREngineTest {

    static PageExtractor extractor;
    static TesseractProvider engine;

    @BeforeAll
    static void setup() {
        extractor = new PageExtractor();
        engine = new TesseractProvider();
    }

    private static PageResult ocrPage(TesseractProvider engine, List<BufferedImage> pages, int pageIndex) throws IOException {
        BufferedImage page = pages.get(pageIndex);
        BufferedImage gray = page.getType() == BufferedImage.TYPE_BYTE_GRAY ? page
                : toGray(page);
        return engine.ocr(gray, pageIndex);
    }

    private static BufferedImage toGray(BufferedImage img) {
        BufferedImage gray = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = gray.createGraphics();
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return gray;
    }

    @Test
    void ocr_simpleText_returnsWordBlocks() throws IOException {
        var pages = extractor.extractPages(new File("tests/simple-text.pdf"));
        var result = ocrPage(engine, pages, 0);
        assertNotNull(result);
        assertNotNull(result.getTextBlocks());
        assertFalse(result.getTextBlocks().isEmpty());
    }

    @Test
    void ocr_simpleText_containsExpectedWord() throws IOException {
        var pages = extractor.extractPages(new File("tests/simple-text.pdf"));
        var result = ocrPage(engine, pages, 0);
        var words = result.getTextBlocks().stream()
                .map(tb -> tb.getWord().toLowerCase())
                .toList();
        assertTrue(words.contains("brown"),
                () -> "Expected 'brown' in OCR output but got: " + words);
    }

    @Test
    void ocr_simpleText_allBlocksHavePositiveConfidence() throws IOException {
        var pages = extractor.extractPages(new File("tests/simple-text.pdf"));
        var result = ocrPage(engine, pages, 0);
        for (var tb : result.getTextBlocks()) {
            assertTrue(tb.getConfidence() >= 0,
                    () -> "Negative confidence for word: " + tb.getWord());
        }
    }

    @Test
    void ocr_blankPage_returnsEmptyBlocks() throws IOException {
        var pages = extractor.extractPages(new File("tests/blank.pdf"));
        var result = ocrPage(engine, pages, 0);
        assertNotNull(result);
        assertTrue(result.getTextBlocks().isEmpty());
    }

    @Test
    void ocr_blankPage_returnsCorrectPageNumber() throws IOException {
        var pages = extractor.extractPages(new File("tests/blank.pdf"));
        var result = ocrPage(engine, pages, 0);
        assertEquals(1, result.getPageNumber());
    }

    @Test
    void ocr_simpleText_blocksHaveBoundingBoxes() throws IOException {
        var pages = extractor.extractPages(new File("tests/simple-text.pdf"));
        var result = ocrPage(engine, pages, 0);
        for (var tb : result.getTextBlocks()) {
            assertNotNull(tb.getBbox());
            assertTrue(tb.getBbox().width > 0);
            assertTrue(tb.getBbox().height > 0);
        }
    }
}
