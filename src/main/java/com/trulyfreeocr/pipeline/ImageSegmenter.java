package com.trulyfreeocr.pipeline;

import java.awt.image.BufferedImage;

import com.trulyfreeocr.model.SegmentedImage;

public class ImageSegmenter {

    private final int tileSize;
    private final double percentile;
    private final int inpaintRadius;

    public ImageSegmenter() {
        this(64, 0.95, 3);
    }

    public ImageSegmenter(int tileSize, double percentile, int inpaintRadius) {
        this.tileSize = tileSize;
        this.percentile = percentile;
        this.inpaintRadius = inpaintRadius;
    }

    /**
     * Orchestrates the full page-segmentation pipeline:
     *   grayscale -> background normalization -> Otsu binarization -> inpaint background.
     *
     * @param image  Input RGB page image (e.g. 300 DPI PDFBox render).
     * @return       SegmentedImage holding a binary foreground mask and a cleaned background.
     */
    public SegmentedImage segment(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();

        // Extract ARGB pixels once, reuse for grayscale + inpainting
        int[] origPixels = new int[width * height];
        image.getRGB(0, 0, width, height, origPixels, 0, width);

        // Step 1: Convert to grayscale (from the cached origPixels)
        int[] gray = toGrayscale(origPixels);

        // Step 2: Background normalization
        int[] bgNormalized = backgroundNormalize(gray, width, height, tileSize);

        // Step 3: Otsu binarization
        int threshold = otsuThreshold(bgNormalized);
        int[] maskPixels = new int[width * height];
        for (int i = 0; i < maskPixels.length; i++) {
            maskPixels[i] = (gray[i] <= threshold) ? 0xFF000000 : 0xFFFFFFFF;
        }
        BufferedImage foregroundMask = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY);
        foregroundMask.setRGB(0, 0, width, height, maskPixels, 0, width);

        // Step 4: Cleaned background — fill text regions with surrounding color
        BufferedImage cleanedBackground = inpaintBackground(origPixels, maskPixels, width, height, inpaintRadius);

        return new SegmentedImage(foregroundMask, cleanedBackground);
    }

    /**
     * Converts a flat ARGB int array to a grayscale int array (0 = black, 255 = white)
     * using the ITU-R BT.601 luma formula.
     *
     * Magic numbers in luma formula:
     *   0.299, 0.587, 0.114 — perception-weighted coefficients for R/G/B.
     *   Human vision is most sensitive to green, least to blue.
     *   These sum to 1.0, so neutral gray (R=G=B) maps to itself.
     *   This is the same formula used in JPEG, NTSC/PAL, and most image libraries.
     *
     * Bit shifts in RGB extraction:
     *   (rgb >> 16) & 0xFF  — extract the red  byte (bits 16-23 of 0xAARRGGBB)
     *   (rgb >>  8) & 0xFF  — extract the green byte (bits  8-15)
     *   rgb        & 0xFF  — extract the blue byte (bits  0-7)
     */
    private int[] toGrayscale(int[] rgba) {
        int[] gray = new int[rgba.length];
        for (int i = 0; i < rgba.length; i++) {
            int rgb = rgba[i];
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            gray[i] = (int) (0.299 * r + 0.587 * g + 0.114 * b);
        }
        return gray;
    }

    /**
     * Tile-based background normalization that corrects non-uniform illumination
     * (e.g. shadows, gradients, scanner vignetting).
     *
     * How it works:
     *   1. Divide the image into a grid of tiles (default 64x64 px).
     *   2. In each tile, estimate the "background level" as the 95th percentile
     *      of pixel intensities. Since text is dark on a light background, the
     *      bright tail of the histogram represents the page surface (background).
     *      The 95th percentile is used (instead of max) to be robust to noise.
     *   3. Build a smooth background surface across the page using bilinear
     *      interpolation between tile centers.
     *   4. Each pixel is then stretched: new_value = old_value * 255 / bg_estimate.
     *      Pixels at or near the background level map to 255 (white); dark text
     *      pixels stay dark because their value is far below the bg estimate.
     *
     * Magic numbers:
     *   tileSize = 64 — balances accuracy vs performance. 2^6 allows fast
     *     integer arithmetic for interpolation (tile centres are at multiples
     *     of 64).  Smaller = more detail but slower; larger = faster but coarser.
     *   0.95 — 95th percentile.  Picked empirically: the top 5% of brightness
     *     values in a tile are assumed to be background, not text or noise.
     *   255.0 — maximum 8-bit grayscale value (white).
     */
    private int[] backgroundNormalize(int[] gray, int width, int height, int tileSize) {
        int tilesX = (int) Math.ceil((double) width / tileSize);
        int tilesY = (int) Math.ceil((double) height / tileSize);

        // Sample background level per tile (95th percentile = bright, since text is dark)
        int[][] bgLevels = new int[tilesY][tilesX];
        for (int ty = 0; ty < tilesY; ty++) {
            for (int tx = 0; tx < tilesX; tx++) {
                int startX = tx * tileSize;
                int startY = ty * tileSize;
                int endX = Math.min(startX + tileSize, width);
                int endY = Math.min(startY + tileSize, height);

                // Build 256-bin histogram for this tile
                int[] hist = new int[256];
                for (int y = startY; y < endY; y++) {
                    int rowOffset = y * width;
                    for (int x = startX; x < endX; x++) {
                        hist[gray[rowOffset + x]]++;
                    }
                }
                int count = (endX - startX) * (endY - startY);

                // Find the value at the percentile index (forward cumulative sum)
                int cum = 0;
                int bgValue = 0;
                int target = (int) (count * percentile);
                for (int v = 0; v < 256; v++) {
                    cum += hist[v];
                    if (cum >= target) {
                        bgValue = v;
                        break;
                    }
                }
                bgLevels[ty][tx] = bgValue;
            }
        }

        // Per-pixel background normalization with integer bilinear interpolation
        int[] result = new int[gray.length];
        int ts2 = tileSize * tileSize;
        for (int y = 0; y < height; y++) {
            int ty0 = Math.min(y / tileSize, tilesY - 1);
            int ty1 = Math.min(ty0 + 1, tilesY - 1);
            int fy = y % tileSize;
            for (int x = 0; x < width; x++) {
                int tx0 = Math.min(x / tileSize, tilesX - 1);
                int tx1 = Math.min(tx0 + 1, tilesX - 1);
                int fx = x % tileSize;

                int top = bgLevels[ty0][tx0] * (tileSize - fx) + bgLevels[ty0][tx1] * fx;
                int bot = bgLevels[ty1][tx0] * (tileSize - fx) + bgLevels[ty1][tx1] * fx;
                int bg = (top * (tileSize - fy) + bot * fy) / ts2;

                int idx = y * width + x;
                if (bg > 0) {
                    int normalized = gray[idx] * 255 / bg;
                    result[idx] = Math.min(255, normalized);
                } else {
                    result[idx] = gray[idx];
                }
            }
        }
        return result;
    }

    /**
     * Otsu's method for automatic image thresholding.
     *
     * Theory: exhaustively search all 256 possible threshold values and pick the
     * one that maximises the inter-class variance between foreground and background
     * pixel distributions.  This gives optimal separation without any user tuning.
     *
     * Algorithm steps:
     *   1. Build a 256-bin histogram of pixel intensities.
     *   2. For each threshold t (0..255), split pixels into two classes:
     *      class B (background, intensities > t) and class F (foreground, ≤ t).
     *   3. Compute the between-class variance:
     *          σ² = wB * wF * (μB - μF)²
     *      where w = weight (proportion of pixels), μ = mean intensity.
     *   4. Return the t that maximises σ².
     *
     * Magic numbers:
     *   256 — number of bins in an 8-bit grayscale histogram (2^8 possible values).
     *   wB, wF  — weights (class pixel counts).  These are integers but the formula
     *     uses them as the product to give heavier weight to balanced splits.
     */
    private int otsuThreshold(int[] pixels) {
        int[] histogram = new int[256];
        for (int p : pixels) {
            histogram[p]++;
        }

        int total = pixels.length;
        double sum = 0;
        for (int i = 0; i < 256; i++) {
            sum += i * histogram[i];
        }

        double sumB = 0;
        int wB = 0;
        double maxVariance = 0;
        int threshold = 0;

        for (int i = 0; i < 256; i++) {
            wB += histogram[i];
            if (wB == 0) continue;
            int wF = total - wB;
            if (wF == 0) break;

            sumB += i * histogram[i];
            double meanB = sumB / wB;
            double meanF = (sum - sumB) / wF;
            double variance = (double) wB * wF * (meanB - meanF) * (meanB - meanF);

            if (variance > maxVariance) {
                maxVariance = variance;
                threshold = i;
            }
        }
        return threshold;
    }

    /**
     * Simple inpainting: replace foreground (text) pixels with the nearest
     * background pixel colour found within a search radius.
     *
     * This produces a "cleaned" page where text regions are filled in with
     * the surrounding page colour — useful as a background layer for PDF
     * re-assembly (the text will be overlaid as an invisible OCR layer).
     *
     * Algorithm:
     *   For each pixel that the mask identifies as foreground (black):
     *     1. Search outward in a square (top-left to bottom-right) within radius.
     *     2. Pick the first background (white in mask) pixel found.
     *     3. Copy that pixel's colour to the result.
     *   Background pixels are copied unchanged.
     *
     * This is a crude "nearest neighbour" inpaint — a real implementation would
     * use a proper diffusion or patch-match algorithm, but this is sufficient
     * for the page-background use case.
     *
     * Magic numbers:
     *   radius — search window size (passed in as parameter; typically 3).
     *     3 is used because typical text stroke widths at 300 DPI are 2-4 px.
     *     Larger values handle thicker strokes but are slower.
     *   0xFFFFFF — bitmask to extract only the RGB channels, discarding alpha.
     *     setRGB expects full ARGB, so foreground is 0xFF000000 (black w/ alpha=FF)
     *     and background is 0xFFFFFFFF (white w/ alpha=FF).
     *   Bit shifts for colour reconstruction:
     *     (bestR << 16) | (bestG << 8) | bestB — packs R, G, B into 0x00RRGGBB.
     *     Alpha is also needed: | 0xFF000000 for full opacity.
     *     (nrgb >> 16) & 0xFF, (nrgb >> 8) & 0xFF, nrgb & 0xFF — extract
     *     individual 8-bit colour channels from a 32-bit ARGB int.
     */
    private BufferedImage inpaintBackground(int[] origPixels, int[] maskPixels, int width, int height, int radius) {
        int[] resultPixels = new int[width * height];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int idx = y * width + x;
                boolean isForeground = (maskPixels[idx] & 0xFFFFFF) == 0;

                if (!isForeground) {
                    resultPixels[idx] = origPixels[idx];
                } else {
                    // Find nearest background pixel within radius
                    int bestR = 0, bestG = 0, bestB = 0;
                    int found = 0;
                    for (int dy = -radius; dy <= radius && found == 0; dy++) {
                        for (int dx = -radius; dx <= radius && found == 0; dx++) {
                            int nx = x + dx;
                            int ny = y + dy;
                            if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue;
                            int nmask = maskPixels[ny * width + nx];
                            if ((nmask & 0xFFFFFF) != 0) {
                                int nrgb = origPixels[ny * width + nx];
                                bestR = (nrgb >> 16) & 0xFF;
                                bestG = (nrgb >> 8) & 0xFF;
                                bestB = nrgb & 0xFF;
                                found++;
                            }
                        }
                    }
                    if (found > 0) {
                        resultPixels[idx] = 0xFF000000 | (bestR << 16) | (bestG << 8) | bestB;
                    } else {
                        resultPixels[idx] = origPixels[idx];
                    }
                }
            }
        }

        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        result.setRGB(0, 0, width, height, resultPixels, 0, width);
        return result;
    }
}
