package com.trulyfreeocr.model;

import java.awt.Rectangle;
import java.util.List;

/**
 * A single word-level OCR result with its bounding box and confidence score.
 *
 * word           The recognised text string.
 * bbox           Bounding box in pixel coordinates
 *                (x, y, width, height; origin at top-left of the page image).
 * confidence     Tesseract's confidence score (0.0 = low, 100.0 = high).
 * charPositions  Per-character source positions in PDF points
 *                ({x, y, width, height} per char), or null when unavailable.
 */
public class TextBlock {

    private final String word;
    private final Rectangle bbox;
    private final double confidence;
    private final List<float[]> charPositions;

    public TextBlock(String word, Rectangle bbox, double confidence) {
        this(word, bbox, confidence, null);
    }

    public TextBlock(String word, Rectangle bbox, double confidence,
                     List<float[]> charPositions) {
        this.word = word;
        this.bbox = bbox;
        this.confidence = confidence;
        this.charPositions = charPositions;
    }

    public String getWord() { return word; }
    public Rectangle getBbox() { return bbox; }
    public double getConfidence() { return confidence; }
    public List<float[]> getCharPositions() { return charPositions; }
}
