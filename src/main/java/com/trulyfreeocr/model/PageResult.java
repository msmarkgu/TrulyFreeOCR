package com.trulyfreeocr.model;

import java.util.List;

/**
 * Immutable result of OCR for a single page.
 *
 * pageNumber  1-based page index within the document.
 * width       Page image width  in pixels (from the rendered BufferedImage).
 * height      Page image height in pixels.
 * textBlocks  List of recognised words with bounding boxes and confidences.
 */
public class PageResult {

    private final int pageNumber;
    private final int width;
    private final int height;
    private final List<TextBlock> textBlocks;

    public PageResult(int pageNumber, int width, int height, List<TextBlock> textBlocks) {
        this.pageNumber = pageNumber;
        this.width = width;
        this.height = height;
        this.textBlocks = textBlocks;
    }

    public int getPageNumber() { return pageNumber; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public List<TextBlock> getTextBlocks() { return textBlocks; }
}
