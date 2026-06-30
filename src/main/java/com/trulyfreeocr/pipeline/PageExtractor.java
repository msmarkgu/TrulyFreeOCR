package com.trulyfreeocr.pipeline;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 * Renders each page of a PDF to a BufferedImage at 300 DPI
 * using PDFBox's PDFRenderer.
 *
 * 300 DPI is the standard scanning resolution for OCR:
 *   - US Letter (8.5"x11")  → 2550 × 3300 px
 *   - A4 (210×297 mm)        → 2480 × 3508 px
 *
 * The renderer uses PDFBox's built-in SFF (subpixel font finalizer)
 * for high-quality text rendering.
 *
 * Implements AutoCloseable so the underlying PDDocument is released.
 * Callers should use try-with-resources.
 */
public class PageExtractor implements AutoCloseable {

    private PDDocument document;
    private final float dpi;

    public PageExtractor() {
        this(300f);
    }

    public PageExtractor(float dpi) {
        this.dpi = dpi;
    }

    /**
     * Loads a PDF and renders every page.
     *
     * @param pdf  Input PDF file.
     * @return List of BufferedImages, one per page, at the configured DPI.
     */
    public List<BufferedImage> extractPages(File pdf) throws IOException {
        document = Loader.loadPDF(pdf);
        PDFRenderer renderer = new PDFRenderer(document);
        int pageCount = document.getNumberOfPages();
        List<BufferedImage> pages = new ArrayList<>(pageCount);
        for (int i = 0; i < pageCount; i++) {
            BufferedImage img = renderer.renderImageWithDPI(i, dpi);
            pages.add(img);
        }
        return pages;
    }

    @Override
    public void close() throws IOException {
        if (document != null) {
            document.close();
        }
    }
}
