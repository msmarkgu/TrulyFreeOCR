package com.trulyfreeocr.model;

import java.awt.image.BufferedImage;

/**
 * Output of the ImageSegmenter pipeline.
 *
 * foregroundMask    Binary image (TYPE_BYTE_BINARY) where black pixels
 *                   represent text/foreground and white is background.
 * cleanedBackground The original page image with text regions inpainted
 *                   (filled in with surrounding colour), suitable as a
 *                   JPEG background layer in the output PDF.
 */
public class SegmentedImage {

    private final BufferedImage foregroundMask;
    private final BufferedImage cleanedBackground;

    public SegmentedImage(BufferedImage foregroundMask, BufferedImage cleanedBackground) {
        this.foregroundMask = foregroundMask;
        this.cleanedBackground = cleanedBackground;
    }

    public BufferedImage getForegroundMask() { return foregroundMask; }
    public BufferedImage getCleanedBackground() { return cleanedBackground; }
}
