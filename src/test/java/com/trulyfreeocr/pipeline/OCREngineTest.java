package com.trulyfreeocr.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.trulyfreeocr.model.PageResult;

class OCREngineTest {

    static PageExtractor extractor;
    static OCREngine engine;

    @BeforeAll
    static void setup() {
        extractor = new PageExtractor();
        engine = new OCREngine();
    }

    private static PageResult ocrPage(OCREngine engine, List<BufferedImage> pages, int pageIndex) throws IOException {
        Files.createDirectories(Path.of("temp"));
        Path tempDir = Files.createTempDirectory(Path.of("temp"), "tfocr-test-");
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
            return engine.ocr(pageIndex, tempDir.toFile());
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
    void ocr_simpleText_returnsWordBlocks() throws IOException {
        var pages = extractor.extractPages(new File("tests/test-files/generated/simple-text.pdf"));
        var result = ocrPage(engine, pages, 0);
        assertNotNull(result);
        assertNotNull(result.getTextBlocks());
        assertFalse(result.getTextBlocks().isEmpty());
    }

    @Test
    void ocr_simpleText_containsExpectedWord() throws IOException {
        var pages = extractor.extractPages(new File("tests/test-files/generated/simple-text.pdf"));
        var result = ocrPage(engine, pages, 0);
        var words = result.getTextBlocks().stream()
                .map(tb -> tb.getWord().toLowerCase())
                .toList();
        assertTrue(words.contains("brown"),
                () -> "Expected 'brown' in OCR output but got: " + words);
    }

    @Test
    void ocr_simpleText_allBlocksHavePositiveConfidence() throws IOException {
        var pages = extractor.extractPages(new File("tests/test-files/generated/simple-text.pdf"));
        var result = ocrPage(engine, pages, 0);
        for (var tb : result.getTextBlocks()) {
            assertTrue(tb.getConfidence() >= 0,
                    () -> "Negative confidence for word: " + tb.getWord());
        }
    }

    @Test
    void ocr_blankPage_returnsEmptyBlocks() throws IOException {
        var pages = extractor.extractPages(new File("tests/test-files/generated/blank.pdf"));
        var result = ocrPage(engine, pages, 0);
        assertNotNull(result);
        assertTrue(result.getTextBlocks().isEmpty());
    }

    @Test
    void ocr_blankPage_returnsCorrectPageNumber() throws IOException {
        var pages = extractor.extractPages(new File("tests/test-files/generated/blank.pdf"));
        var result = ocrPage(engine, pages, 0);
        assertEquals(1, result.getPageNumber());
    }

    @Test
    void ocr_simpleText_blocksHaveBoundingBoxes() throws IOException {
        var pages = extractor.extractPages(new File("tests/test-files/generated/simple-text.pdf"));
        var result = ocrPage(engine, pages, 0);
        for (var tb : result.getTextBlocks()) {
            assertNotNull(tb.getBbox());
            assertTrue(tb.getBbox().width > 0);
            assertTrue(tb.getBbox().height > 0);
        }
    }
}
