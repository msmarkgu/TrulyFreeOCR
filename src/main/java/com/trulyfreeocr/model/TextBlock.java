package com.trulyfreeocr.model;

import java.awt.Rectangle;

/**
 * A single word-level OCR result with its bounding box and confidence score.
 *
 * word       The recognised text string.
 * bbox       Bounding box in pixel coordinates
 *            (x, y, width, height; origin at top-left of the page image).
 * confidence Tesseract's confidence score (0.0 = low, 100.0 = high).
 */
public class TextBlock {

    private final String word;
    private final Rectangle bbox;
    private final double confidence;

    public TextBlock(String word, Rectangle bbox, double confidence) {
        this.word = word;
        this.bbox = bbox;
        this.confidence = confidence;
    }

    public String getWord() { return word; }
    public Rectangle getBbox() { return bbox; }
    public double getConfidence() { return confidence; }
}
