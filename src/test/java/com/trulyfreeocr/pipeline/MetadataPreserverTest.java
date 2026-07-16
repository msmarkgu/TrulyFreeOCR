package com.trulyfreeocr.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.trulyfreeocr.model.PageResult;
import com.trulyfreeocr.model.SegmentedImage;

class MetadataPreserverTest {

    static PageExtractor extractor;
    static ImageSegmenter segmenter;
    static TesseractProvider engine;
    static PDFAssembler assembler;
    static MetadataPreserver preserver;

    @BeforeAll
    static void setup() {
        extractor = new PageExtractor();
        segmenter = new ImageSegmenter();
        engine = new TesseractProvider();
        assembler = new PDFAssembler();
        preserver = new MetadataPreserver();
    }

    private static List<PageResult> ocrPages(TesseractProvider engine, List<BufferedImage> pages) throws IOException {
        List<PageResult> results = new ArrayList<>(pages.size());
        for (int i = 0; i < pages.size(); i++) {
            BufferedImage page = pages.get(i);
            BufferedImage gray = page.getType() == BufferedImage.TYPE_BYTE_GRAY ? page
                    : toGray(page);
            results.add(engine.ocr(gray, i));
        }
        return results;
    }

    private static BufferedImage toGray(BufferedImage img) {
        BufferedImage gray = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = gray.createGraphics();
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return gray;
    }

    @Test
    void preserve_copiesDocumentInfo() throws IOException {
        File input = new File("tests/simple-text.pdf");
        var pages = extractor.extractPages(input);
        var backgrounds = pages.stream()
                .map(segmenter::segment)
                .map(SegmentedImage::getCleanedBackground)
                .toList();
        var ocrResults = ocrPages(engine, pages);

        try (PDDocument source = Loader.loadPDF(input);
             PDDocument output = assembler.assemble(input, backgrounds, null, ocrResults, false)) {
            List<PDPage> outPages = new ArrayList<>();
            for (int i = 0; i < output.getNumberOfPages(); i++) {
                outPages.add(output.getPage(i));
            }
            preserver.preserve(source, output, outPages);

            var srcInfo = source.getDocumentInformation();
            var dstInfo = output.getDocumentInformation();
            assertEquals(srcInfo.getTitle(), dstInfo.getTitle());
            assertEquals(srcInfo.getAuthor(), dstInfo.getAuthor());
            assertEquals(srcInfo.getSubject(), dstInfo.getSubject());
            assertEquals(srcInfo.getKeywords(), dstInfo.getKeywords());
        }
    }

    @Test
    void preserve_copiesOutline() throws IOException {
        File input = new File("tests/with-annotations.pdf");
        var pages = extractor.extractPages(input);
        var backgrounds = pages.stream()
                .map(segmenter::segment)
                .map(SegmentedImage::getCleanedBackground)
                .toList();
        var ocrResults = ocrPages(engine, pages);

        try (PDDocument source = Loader.loadPDF(input);
             PDDocument output = assembler.assemble(input, backgrounds, null, ocrResults, false)) {
            List<PDPage> outPages = new ArrayList<>();
            for (int i = 0; i < output.getNumberOfPages(); i++) {
                outPages.add(output.getPage(i));
            }
            preserver.preserve(source, output, outPages);

            // Verify outline was copied
            PDDocumentOutline srcOutline = source.getDocumentCatalog().getDocumentOutline();
            PDDocumentOutline dstOutline = output.getDocumentCatalog().getDocumentOutline();
            if (srcOutline != null) {
                assertNotNull(dstOutline, "Outline should be copied when source has one");
                int srcCount = 0;
                for (var child : srcOutline.children()) srcCount++;
                int dstCount = 0;
                for (var child : dstOutline.children()) dstCount++;
                assertEquals(srcCount, dstCount, "Outline item count should match");
            }
        }
    }

    @Test
    void preserve_copiesAnnotations() throws IOException {
        File input = new File("tests/with-annotations.pdf");
        var pages = extractor.extractPages(input);
        var backgrounds = pages.stream()
                .map(segmenter::segment)
                .map(SegmentedImage::getCleanedBackground)
                .toList();
        var ocrResults = ocrPages(engine, pages);

        try (PDDocument source = Loader.loadPDF(input);
             PDDocument output = assembler.assemble(input, backgrounds, null, ocrResults, false)) {
            List<PDPage> outPages = new ArrayList<>();
            for (int i = 0; i < output.getNumberOfPages(); i++) {
                outPages.add(output.getPage(i));
            }
            preserver.preserve(source, output, outPages);

            for (int i = 0; i < source.getNumberOfPages() && i < output.getNumberOfPages(); i++) {
                int srcCount = source.getPage(i).getAnnotations().size();
                int dstCount = output.getPage(i).getAnnotations().size();
                assertEquals(srcCount, dstCount,
                        "Page " + (i + 1) + " annotation count should match");
            }
        }
    }
}
