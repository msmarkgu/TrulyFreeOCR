package com.trulyfreeocr.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for JBIG2Compressor with synthetic binary images.
 *
 * Since jbig2enc is not available in this environment, all tests exercise
 * the CCITT G4 fallback path.  Verifies non-null outputs, dimension preservation,
 * and correct behaviour for blank, all-white, and mixed foreground images.
 */
class JBIG2CompressorTest {

    static JBIG2Compressor compressor;
    static JBIG2Compressor fallbackCompressor;

    @BeforeAll
    static void setup() {
        compressor = new JBIG2Compressor();
        fallbackCompressor = new JBIG2Compressor("/nonexistent");
    }

    @Test
    void compress_blankImage_returnsNonNull() throws IOException {
        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_BYTE_BINARY);
        JBIG2Compressor.CompressionResult result = compressor.compress(img);
        assertNotNull(result);
        assertNotNull(result.getData());
    }

    @Test
    void compress_preservesDimensions() throws IOException {
        BufferedImage img = new BufferedImage(200, 300, BufferedImage.TYPE_BYTE_BINARY);
        JBIG2Compressor.CompressionResult result = compressor.compress(img);
        assertEquals(200, result.getWidth());
        assertEquals(300, result.getHeight());
    }

    @Test
    void compress_allWhite_returnsNonNull() throws IOException {
        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_BYTE_BINARY);
        for (int y = 0; y < 100; y++) {
            for (int x = 0; x < 100; x++) {
                img.setRGB(x, y, 0xFFFFFFFF);
            }
        }
        JBIG2Compressor.CompressionResult result = compressor.compress(img);
        assertNotNull(result);
        assertNotNull(result.getData());
    }

    @Test
    void compress_withForegroundPixels_returnsNonNull() throws IOException {
        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_BYTE_BINARY);
        for (int y = 0; y < 100; y++) {
            for (int x = 0; x < 100; x++) {
                img.setRGB(x, y, 0xFFFFFFFF);
            }
        }
        for (int x = 10; x < 90; x++) {
            img.setRGB(x, 50, 0xFF000000);
        }
        JBIG2Compressor.CompressionResult result = compressor.compress(img);
        assertNotNull(result);
        assertNotNull(result.getData());
    }

    @Test
    void compress_fallbackIsNotJbig2() throws IOException {
        BufferedImage img = new BufferedImage(50, 50, BufferedImage.TYPE_BYTE_BINARY);
        JBIG2Compressor.CompressionResult result = fallbackCompressor.compress(img);
        assertNotNull(result);
        assertFalse(result.isJbig2(), "Should fallback when jbig2enc unavailable");
    }

    /**
     * Regression test for the batch-JBIG2 shared-dictionary bug.
     *
     * Verifies that compressAllFromDir returns null (graceful fallback)
     * when jbig2enc is not available, proving the API doesn't throw.
     */
    @Test
    void compressAllFromDir_returnsNullWhenJbig2encMissing() throws IOException {
        JBIG2Compressor missing = new JBIG2Compressor("/nonexistent");
        JBIG2Compressor.BatchResult result = missing.compressAllFromDir(
            new File(System.getProperty("java.io.tmpdir")), 2,
            new int[]{100, 100}, new int[]{100, 100});
        assertNull(result, "Should return null when jbig2enc not found");
    }

    /**
     * Regression test for the batch-JBIG2 shared-dictionary bug.
     *
     * When jbig2enc IS available, compressAllFromDir should produce a
     * BatchResult with a non-empty global symbol dictionary and per-page data.
     * When unavailable the test is silently skipped.
     */
    @Test
    void compressAllFromDir_returnsBatchResultWhenJbig2encAvailable() throws IOException {
        JBIG2Compressor realCompressor = new JBIG2Compressor();

        // Probe availability by running on a single mask
        File probeDir = new File(System.getProperty("java.io.tmpdir"), "tfocr-jbig2-probe-"
            + System.nanoTime());
        probeDir.mkdirs();
        BufferedImage probe = new BufferedImage(50, 50, BufferedImage.TYPE_BYTE_BINARY);
        probe.setRGB(25, 25, 0xFF000000);
        ImageIO.write(probe, "bmp", new File(probeDir, "mask-0.bmp"));
        JBIG2Compressor.BatchResult probeResult = realCompressor.compressAllFromDir(probeDir, 1,
            new int[]{50}, new int[]{50});
        for (File f : probeDir.listFiles()) f.delete();
        probeDir.delete();

        if (probeResult == null) {
            return; // jbig2enc not available — skip
        }

        // Create two masks with different text patterns
        File maskDir = new File(System.getProperty("java.io.tmpdir"), "tfocr-jbig2-test-"
            + System.nanoTime());
        maskDir.mkdirs();
        try {
            for (int i = 0; i < 2; i++) {
                BufferedImage mask = new BufferedImage(100, 100, BufferedImage.TYPE_BYTE_BINARY);
                for (int x = 10; x < 90; x++) {
                    mask.setRGB(x, 50 + i * 20, 0xFF000000);
                }
                ImageIO.write(mask, "bmp", new File(maskDir, "mask-" + i + ".bmp"));
            }

            JBIG2Compressor.BatchResult result = realCompressor.compressAllFromDir(maskDir, 2,
                new int[]{100, 100}, new int[]{100, 100});
            assertNotNull(result,
                "BatchResult should not be null when jbig2enc is available");
            assertNotNull(result.getGlobalSym(),
                "Global symbol dictionary should not be null");
            assertTrue(result.getGlobalSym().length > 0,
                "Global symbol dictionary should have data");
            assertEquals(2, result.getPages().size(),
                "Should have per-page data for 2 pages");
            for (int i = 0; i < 2; i++) {
                JBIG2Compressor.CompressionResult page = result.getPages().get(i);
                assertEquals(100, page.getWidth());
                assertEquals(100, page.getHeight());
                assertTrue(page.isJbig2(), "Batch page should be JBIG2 (not fallback)");
            }
        } finally {
            for (File f : maskDir.listFiles()) f.delete();
            maskDir.delete();
        }
    }

    /**
     * Regression test for mixed page-size JBIG2 dimensions (Bug 3).
     *
     * Verifies that per-page dimensions are correctly stored in each
     * CompressionResult when different pages have different sizes.
     */
    @Test
    void compressAllFromDir_preservesPerPageDimensions() throws IOException {
        JBIG2Compressor realCompressor = new JBIG2Compressor();

        // Probe availability
        File probeDir = new File(System.getProperty("java.io.tmpdir"), "tfocr-jbig2-probe-"
            + System.nanoTime());
        probeDir.mkdirs();
        BufferedImage probe = new BufferedImage(50, 50, BufferedImage.TYPE_BYTE_BINARY);
        probe.setRGB(25, 25, 0xFF000000);
        ImageIO.write(probe, "bmp", new File(probeDir, "mask-0.bmp"));
        JBIG2Compressor.BatchResult probeResult = realCompressor.compressAllFromDir(probeDir, 1,
            new int[]{50}, new int[]{50});
        for (File f : probeDir.listFiles()) f.delete();
        probeDir.delete();

        if (probeResult == null) {
            return; // jbig2enc not available — skip
        }

        // Different page sizes: page 0 = 100x200, page 1 = 150x250
        File maskDir = new File(System.getProperty("java.io.tmpdir"), "tfocr-jbig2-mixed-"
            + System.nanoTime());
        maskDir.mkdirs();
        try {
            for (int i = 0; i < 2; i++) {
                int w = i == 0 ? 100 : 150;
                int h = i == 0 ? 200 : 250;
                BufferedImage mask = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_BINARY);
                for (int x = 10; x < w - 10; x++) {
                    mask.setRGB(x, h / 2, 0xFF000000);
                }
                ImageIO.write(mask, "bmp", new File(maskDir, "mask-" + i + ".bmp"));
            }

            JBIG2Compressor.BatchResult result = realCompressor.compressAllFromDir(maskDir, 2,
                new int[]{100, 150}, new int[]{200, 250});
            assertNotNull(result, "BatchResult should not be null");
            assertEquals(2, result.getPages().size());

            assertEquals(100, result.getPages().get(0).getWidth(),
                "Page 0 width should be 100");
            assertEquals(200, result.getPages().get(0).getHeight(),
                "Page 0 height should be 200");
            assertEquals(150, result.getPages().get(1).getWidth(),
                "Page 1 width should be 150");
            assertEquals(250, result.getPages().get(1).getHeight(),
                "Page 1 height should be 250");
        } finally {
            for (File f : maskDir.listFiles()) f.delete();
            maskDir.delete();
        }
    }
}
