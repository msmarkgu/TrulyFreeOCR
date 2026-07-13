package com.trulyfreeocr.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.Test;

class PaddleOcrOnnxProviderTest {

    @Test
    void ctcDecode_handlesBlankOnly() throws Exception {
        Method method = PaddleOcrOnnxProvider.class.getDeclaredMethod(
                "ctcDecode", float[][].class, List.class);
        method.setAccessible(true);

        // All timesteps predict blank (index 0)
        float[][] logits = new float[3][5];
        logits[0][0] = 10; logits[1][0] = 10; logits[2][0] = 10;

        List<String> dict = List.of("", "a", "b", "c", " ");
        String result = (String) method.invoke(null, logits, dict);
        assertEquals("", result);
    }

    @Test
    void ctcDecode_betweenBlankSeparatesRepeatedChars() throws Exception {
        Method method = PaddleOcrOnnxProvider.class.getDeclaredMethod(
                "ctcDecode", float[][].class, List.class);
        method.setAccessible(true);

        // Model: [blank, a, blank, a, a, b, blank]
        // CTC: blank separates repeats → "aab" (two a's separated by blank)
        float[][] logits = new float[7][5];
        logits[0][0] = 10; // blank
        logits[1][1] = 10; // 'a'
        logits[2][0] = 10; // blank
        logits[3][1] = 10; // 'a'
        logits[4][1] = 10; // 'a' — collapse with preceding 'a' (no blank between)
        logits[5][2] = 10; // 'b'
        logits[6][0] = 10; // blank

        List<String> dict = List.of("", "a", "b", "c", " ");
        String result = (String) method.invoke(null, logits, dict);
        assertEquals("aab", result);
    }

    @Test
    void ctcDecode_adjacentSameCharsCollapse() throws Exception {
        Method method = PaddleOcrOnnxProvider.class.getDeclaredMethod(
                "ctcDecode", float[][].class, List.class);
        method.setAccessible(true);

        // Model: [a, a, blank, b] — adjacent 'a's collapse to one
        float[][] logits = new float[4][5];
        logits[0][1] = 10; // 'a'
        logits[1][1] = 10; // 'a' — collapse with t=0
        logits[2][0] = 10; // blank
        logits[3][2] = 10; // 'b'

        List<String> dict = List.of("", "a", "b", "c", " ");
        String result = (String) method.invoke(null, logits, dict);
        assertEquals("ab", result);
    }

    @Test
    void ctcDecode_includesSpace() throws Exception {
        Method method = PaddleOcrOnnxProvider.class.getDeclaredMethod(
                "ctcDecode", float[][].class, List.class);
        method.setAccessible(true);

        // Model: [a, blank, space, b]
        float[][] logits = new float[4][5];
        logits[0][1] = 10; // 'a'
        logits[1][0] = 10; // blank
        logits[2][4] = 10; // space (index 4)
        logits[3][2] = 10; // 'b'

        List<String> dict = List.of("", "a", "b", "c", " ");
        String result = (String) method.invoke(null, logits, dict);
        assertEquals("a b", result);
    }

    @Test
    void ctcDecode_skipsOutOfRangeIndices() throws Exception {
        Method method = PaddleOcrOnnxProvider.class.getDeclaredMethod(
                "ctcDecode", float[][].class, List.class);
        method.setAccessible(true);

        // Index 99 is beyond dict size (5)
        float[][] logits = new float[2][100];
        logits[0][1] = 10; // 'a' (valid)
        logits[1][99] = 10; // out of range

        List<String> dict = List.of("", "a", "b", "c", " ");
        String result = (String) method.invoke(null, logits, dict);
        assertEquals("a", result);
    }

    @Test
    void bilinearInterpolate_returnsExactColorOnIntegerCoords() throws Exception {
        Method method = PaddleOcrOnnxProvider.class.getDeclaredMethod(
                "bilinearInterpolate", BufferedImage.class, double.class, double.class);
        method.setAccessible(true);

        BufferedImage img = new BufferedImage(3, 3, BufferedImage.TYPE_3BYTE_BGR);
        // Set pixel (1,1) to red
        img.setRGB(1, 1, 0xFF0000);

        int result = (int) method.invoke(null, img, 1.0, 1.0);
        assertEquals(0xFF0000, result & 0xFFFFFF);
    }

    @Test
    void bilinearInterpolate_interpolatesBetweenPixels() throws Exception {
        Method method = PaddleOcrOnnxProvider.class.getDeclaredMethod(
                "bilinearInterpolate", BufferedImage.class, double.class, double.class);
        method.setAccessible(true);

        BufferedImage img = new BufferedImage(2, 2, BufferedImage.TYPE_3BYTE_BGR);
        // Black at (0,0), white at (1,0), white at (0,1), white at (1,1)
        img.setRGB(0, 0, 0x000000);
        img.setRGB(1, 0, 0xFFFFFF);
        img.setRGB(0, 1, 0xFFFFFF);
        img.setRGB(1, 1, 0xFFFFFF);

        // Midpoint between black and white corners → should be ~192,192,192 (75% white)
        int result = (int) method.invoke(null, img, 0.5, 0.5);
        int r = (result >> 16) & 0xFF;
        int g = (result >> 8) & 0xFF;
        int b = result & 0xFF;
        // At 0.5,0.5: 25% black (0,0,0) + 75% white (255,255,255) = (191.25, 191.25, 191.25)
        assertEquals(191, r, 2);
        assertEquals(191, g, 2);
        assertEquals(191, b, 2);
    }

    @Test
    void bilinearInterpolate_handlesLastPixelEdge() throws Exception {
        Method method = PaddleOcrOnnxProvider.class.getDeclaredMethod(
                "bilinearInterpolate", BufferedImage.class, double.class, double.class);
        method.setAccessible(true);

        BufferedImage img = new BufferedImage(2, 2, BufferedImage.TYPE_3BYTE_BGR);
        img.setRGB(0, 0, 0x000000);
        img.setRGB(1, 0, 0x000000);
        img.setRGB(0, 1, 0x000000);
        img.setRGB(1, 1, 0xFFFFFF);

        // At (1.0, 1.0) → exact pixel (1,1) which is white
        int result = (int) method.invoke(null, img, 0.99, 0.99);
        int b = result & 0xFF;
        assertTrue(b > 200, "Near (1,1) should be nearly white");
    }

    @Test
    void bilinearInterpolate_handlesZeroZero() throws Exception {
        Method method = PaddleOcrOnnxProvider.class.getDeclaredMethod(
                "bilinearInterpolate", BufferedImage.class, double.class, double.class);
        method.setAccessible(true);

        BufferedImage img = new BufferedImage(2, 2, BufferedImage.TYPE_3BYTE_BGR);
        img.setRGB(0, 0, 0x000000);

        int result = (int) method.invoke(null, img, 0.0, 0.0);
        assertEquals(0x000000, result & 0xFFFFFF);
    }
}
