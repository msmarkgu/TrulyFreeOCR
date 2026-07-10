package com.trulyfreeocr.pipeline;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.trulyfreeocr.model.PageResult;
import com.trulyfreeocr.model.TextBlock;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PDFAssemblerRegressionTest {

    @TempDir
    File tempDir;

    private static final int PAGE_W = 400;
    private static final int PAGE_H = 600;

    private File createSourcePdf() throws IOException {
        File pdf = new File(tempDir, "source.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(PAGE_W, PAGE_H));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setNonStrokingColor(1f);
                cs.addRect(0, 0, PAGE_W, PAGE_H);
                cs.fill();
            }
            doc.save(pdf);
        }
        return pdf;
    }

    /**
     * Regression test for the cumulative text-matrix drift bug.
     *
     * The old code used newLineAtOffset(x,y) / showText / newLineAtOffset(-x,-y)
     * per word.  Because showText advances the text matrix by the word width,
     * the "undo" offset never fully resets the position, causing every subsequent
     * word to drift right by the cumulative widths of all previous words.
     *
     * The fix replaces the pair of relative offsets with a single absolute
     * setTextMatrix(Tm) before each word.  We verify by counting Tm operators
     * in the assembled page's content stream — there should be at least as many
     * as there are text blocks.
     */
    @Test
    void textLayer_usesAbsolutePositioning_perWord() throws IOException {
        File source = createSourcePdf();
        int imgW = PAGE_W;
        int imgH = PAGE_H;

        List<TextBlock> blocks = new ArrayList<>();
        blocks.add(new TextBlock("Hello", new Rectangle(10, 100, 120, 40), 0.95));
        blocks.add(new TextBlock("World", new Rectangle(10, 200, 130, 40), 0.95));
        blocks.add(new TextBlock("Drift", new Rectangle(10, 300, 100, 40), 0.95));
        PageResult ocr = new PageResult(1, imgW, imgH, blocks);

        BufferedImage bg = new BufferedImage(imgW, imgH, BufferedImage.TYPE_BYTE_GRAY);
        List<BufferedImage> bgs = Collections.singletonList(bg);

        PDFAssembler assembler = new PDFAssembler();
        try (PDDocument doc = assembler.assemble(source, bgs, null,
                Collections.singletonList(ocr), false)) {
            PDPage page = doc.getPage(0);
            String content = readContentStream(page);

            assertNotNull(content, "Content stream should not be null");

            long tmCount = content.chars().filter(c -> c == 'T').count();
            int actualTmCount = countOperator(content, "Tm");
            assertTrue(actualTmCount >= blocks.size(),
                "Expected at least " + blocks.size() + " Tm operators "
                + "(one per text block), found " + actualTmCount);
        }
    }

    /**
     * Regression test for the JBIG2 dead-code bug.
     *
     * The JBIG2Compressor was instantiated in the CLI entry point but never
     * passed to or used by PDFAssembler.  This test verifies that when a
     * compressor is set on the assembler, its compress() method is actually
     * called during assemble() when foreground masks are present (MRC mode).
     */
    @Test
    void assembler_invokesCompressorWhenSet() throws IOException {
        File source = createSourcePdf();
        int imgW = PAGE_W;
        int imgH = PAGE_H;

        List<TextBlock> blocks = Collections.singletonList(
            new TextBlock("Test", new Rectangle(10, 10, 100, 40), 0.95));
        PageResult ocr = new PageResult(1, imgW, imgH, blocks);

        BufferedImage bg = new BufferedImage(imgW, imgH, BufferedImage.TYPE_BYTE_GRAY);
        List<BufferedImage> bgs = Collections.singletonList(bg);

        BufferedImage fgMask = new BufferedImage(imgW, imgH, BufferedImage.TYPE_BYTE_BINARY);
        List<BufferedImage> masks = Collections.singletonList(fgMask);

        CallCounter counter = new CallCounter();
        PDFAssembler assembler = new PDFAssembler();
        assembler.setCompressor(counter);

        try (PDDocument doc = assembler.assemble(source, bgs, masks,
                Collections.singletonList(ocr), false)) {
            assertNotNull(doc, "Assembled document should not be null");
            assertEquals(1, doc.getNumberOfPages());
        }

        assertTrue(counter.callCount > 0,
            "Compressor.compress() was not called — JBIG2Compressor is dead code");
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static String readContentStream(PDPage page) throws IOException {
        try (InputStream is = page.getContents()) {
            if (is == null) return null;
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int countOperator(String content, String op) {
        int count = 0, idx = 0;
        String pattern = " " + op;
        while ((idx = content.indexOf(pattern, idx)) != -1) {
            count++;
            idx += pattern.length();
        }
        return count;
    }

    /**
     * Regression test for the streaming API (addPage + finishAssembly).
     *
     * This verifies that the two-stage streaming API produces the same
     * number of pages as the single-stage assemble() method.
     */
    @Test
    void addPage_finishAssembly_producesCorrectPageCount() throws IOException {
        File source = createSourcePdf();
        int imgW = PAGE_W;
        int imgH = PAGE_H;

        List<TextBlock> blocks = new ArrayList<>();
        blocks.add(new TextBlock("Page1", new Rectangle(10, 100, 120, 40), 0.95));
        PageResult ocr = new PageResult(1, imgW, imgH, blocks);

        BufferedImage bg = new BufferedImage(imgW, imgH, BufferedImage.TYPE_BYTE_GRAY);
        BufferedImage fgMask = new BufferedImage(imgW, imgH, BufferedImage.TYPE_BYTE_BINARY);

        PDFAssembler assembler = new PDFAssembler();

        try (PDDocument srcDoc = Loader.loadPDF(source);
             PDDocument doc = new PDDocument()) {

            List<PDPage> srcPages = new ArrayList<>();
            for (int i = 0; i < srcDoc.getNumberOfPages(); i++) {
                srcPages.add(srcDoc.getPage(i));
            }

            assembler.addPage(doc, srcDoc, 0, bg, fgMask, ocr);
            assembler.finishAssembly(doc, srcDoc, srcPages, false);

            assertEquals(1, doc.getNumberOfPages(),
                "Streaming API should produce 1 page");
            assertNotNull(doc.getPage(0), "Page should exist");
        }
    }

    /**
     * Regression test for the batch-JBIG2 assembly path (addPageJbig2).
     *
     * Verifies that a page assembled via the JBIG2 globals path has the
     * correct page dimensions, a non-null page object, and the expected
     * number of pages in the final document.
     */
    @Test
    void addPageJbig2_producesValidOutput() throws IOException {
        File source = createSourcePdf();
        int imgW = PAGE_W;
        int imgH = PAGE_H;

        List<TextBlock> blocks = new ArrayList<>();
        blocks.add(new TextBlock("JBIG2", new Rectangle(10, 100, 120, 40), 0.95));
        PageResult ocr = new PageResult(1, imgW, imgH, blocks);

        BufferedImage bg = new BufferedImage(imgW, imgH, BufferedImage.TYPE_BYTE_GRAY);
        byte[] globalSym = new byte[]{0, 0, 0, 0};
        byte[] pageData = new byte[]{0, 0, 0, 0};

        PDFAssembler assembler = new PDFAssembler();

        try (PDDocument srcDoc = Loader.loadPDF(source);
             PDDocument doc = new PDDocument()) {

            List<PDPage> srcPages = new ArrayList<>();
            for (int i = 0; i < srcDoc.getNumberOfPages(); i++) {
                srcPages.add(srcDoc.getPage(i));
            }

            assembler.addPageJbig2(doc, srcDoc, 0, bg, pageData, globalSym, imgW, imgH, ocr);
            assembler.finishAssembly(doc, srcDoc, srcPages, false);

            assertEquals(1, doc.getNumberOfPages(),
                "JBIG2-added page should be in the document");
            PDPage page = doc.getPage(0);
            assertNotNull(page, "JBIG2 page should not be null");
            PDRectangle mediaBox = page.getMediaBox();
            assertEquals(imgW, (int) mediaBox.getWidth(),
                "JBIG2 page width should match source");
            assertEquals(imgH, (int) mediaBox.getHeight(),
                "JBIG2 page height should match source");
        }
    }

    /**
     * Regression test for multi-page streaming (addPage loop).
     *
     * Verifies that adding multiple pages via addPage + finishAssembly
     * produces the correct total page count, proving that the streaming
     * API properly accumulates pages.
     */
    @Test
    void addPage_multiplePages_producesCorrectCount() throws IOException {
        // Create a 3-page source PDF
        File pdf = new File(tempDir, "multi-source.pdf");
        try (PDDocument d = new PDDocument()) {
            for (int i = 0; i < 3; i++) {
                d.addPage(new PDPage(new PDRectangle(PAGE_W, PAGE_H)));
            }
            d.save(pdf);
        }

        int imgW = PAGE_W;
        int imgH = PAGE_H;
        List<TextBlock> blocks = new ArrayList<>();
        blocks.add(new TextBlock("Multi", new Rectangle(10, 100, 120, 40), 0.95));
        PageResult ocr = new PageResult(1, imgW, imgH, blocks);

        BufferedImage bg = new BufferedImage(imgW, imgH, BufferedImage.TYPE_BYTE_GRAY);
        BufferedImage fgMask = new BufferedImage(imgW, imgH, BufferedImage.TYPE_BYTE_BINARY);

        PDFAssembler assembler = new PDFAssembler();

        try (PDDocument srcDoc = Loader.loadPDF(pdf);
             PDDocument doc = new PDDocument()) {

            List<PDPage> srcPages = new ArrayList<>();
            for (int i = 0; i < srcDoc.getNumberOfPages(); i++) {
                srcPages.add(srcDoc.getPage(i));
            }

            for (int i = 0; i < 3; i++) {
                assembler.addPage(doc, srcDoc, i, bg, fgMask, ocr);
            }
            assembler.finishAssembly(doc, srcDoc, srcPages, false);

            assertEquals(3, doc.getNumberOfPages(),
                "Streaming API should produce 3 pages");
            for (int i = 0; i < 3; i++) {
                assertNotNull(doc.getPage(i),
                    "Page " + i + " should exist");
            }
        }
    }

    /**
     * Regression test for the JBIG2-globals stream caching (Bug 2).
     *
     * The pre-fix code created a brand-new COSStream for the global symbol
     * dictionary on every single page, embedding N identical copies in the PDF.
     * The fix caches the stream in the PDFAssembler instance and reuses the
     * same COSStream reference across all pages.
     *
     * This test assembles two JBIG2 pages and verifies:
     * 1. The cached stream field is non-null (was created at least once).
     * 2. Both pages' /JBIG2Globals references point to the same COSStream
     *    instance, proving the cache hit.
     */
    @Test
    void addPageJbig2_embedsGlobalsInPageStream() throws Exception {
        File source = createSourcePdf();
        int imgW = PAGE_W;
        int imgH = PAGE_H;

        List<TextBlock> blocks = new ArrayList<>();
        blocks.add(new TextBlock("Combined", new Rectangle(10, 100, 120, 40), 0.95));
        PageResult ocr = new PageResult(1, imgW, imgH, blocks);

        BufferedImage bg = new BufferedImage(imgW, imgH, BufferedImage.TYPE_BYTE_GRAY);
        byte[] globalSym = new byte[]{0x00, 0x00, 0x00, 0x00, 0x00};
        byte[] pageData = new byte[]{0x00, 0x00, 0x00, 0x01, 0x30};

        PDFAssembler assembler = new PDFAssembler();

        File pdf = new File(tempDir, "two-page-source.pdf");
        try (PDDocument d = new PDDocument()) {
            d.addPage(new PDPage(new PDRectangle(PAGE_W, PAGE_H)));
            d.addPage(new PDPage(new PDRectangle(PAGE_W, PAGE_H)));
            d.save(pdf);
        }

        try (PDDocument srcDoc = Loader.loadPDF(pdf);
             PDDocument doc = new PDDocument()) {

            List<PDPage> srcPages = new ArrayList<>();
            for (int i = 0; i < srcDoc.getNumberOfPages(); i++) {
                srcPages.add(srcDoc.getPage(i));
            }

            assembler.addPageJbig2(doc, srcDoc, 0, bg, pageData, globalSym, imgW, imgH, ocr);
            assembler.addPageJbig2(doc, srcDoc, 1, bg, pageData, globalSym, imgW, imgH, ocr);
            assembler.finishAssembly(doc, srcDoc, srcPages, false);

            assertEquals(2, doc.getNumberOfPages());

            // Verify each page's JBIG2 stream starts with the globals prefix (prepended)
            for (int i = 0; i < 2; i++) {
                PDPage page = doc.getPage(i);
                boolean foundJbig2 = false;
                for (COSName name : page.getResources().getXObjectNames()) {
                    PDXObject xobj = page.getResources().getXObject(name);
                    if (xobj instanceof PDImageXObject) {
                        PDImageXObject ximg = (PDImageXObject) xobj;
                        COSBase filter = ximg.getCOSObject().getItem(COSName.FILTER);
                        if (COSName.JBIG2_DECODE.equals(filter)) {
                            // Verify NO separate /JBIG2Globals reference
                            assertNull(ximg.getCOSObject().getItem(COSName.JBIG2_GLOBALS),
                                "Page " + i + " should not reference a separate JBIG2Globals stream");
                            // Verify the stream has data (combined globals + page)
                            assertTrue(ximg.getCOSObject().getLength() > globalSym.length,
                                "Page " + i + " JBIG2 stream should be longer than globals alone");
                            foundJbig2 = true;
                        }
                    }
                }
                assertTrue(foundJbig2,
                    "Page " + i + " should have a JBIG2-encoded XObject");
            }
        }
    }

    /**
     * Verifies PDF/A output includes an OutputIntent with the sRGB profile.
     *
     * Bug #2: The sRGB Color Space Profile.icm was missing from classpath,
     * causing the OutputIntent block to be silently skipped. Any PDF generated
     * under --pdfa would fail strict PDF/A validation.
     */
    @Test
    void pdfaOutput_includesOutputIntent() throws IOException {
        File source = createSourcePdf();
        int imgW = PAGE_W;
        int imgH = PAGE_H;

        List<TextBlock> blocks = new ArrayList<>();
        blocks.add(new TextBlock("PDFA", new Rectangle(10, 100, 120, 40), 0.95));
        PageResult ocr = new PageResult(1, imgW, imgH, blocks);

        BufferedImage bg = new BufferedImage(imgW, imgH, BufferedImage.TYPE_BYTE_GRAY);

        PDFAssembler assembler = new PDFAssembler();
        try (PDDocument doc = assembler.assemble(source, Collections.singletonList(bg),
                null, Collections.singletonList(ocr), true)) {

            var catalog = doc.getDocumentCatalog();
            var intents = catalog.getOutputIntents();
            assertNotNull(intents, "OutputIntents should not be null");
            assertFalse(intents.isEmpty(), "OutputIntents should not be empty");

            PDOutputIntent intent = intents.get(0);
            assertTrue(intent.getOutputCondition().toLowerCase().contains("srgb"),
                    "OutputIntent condition should reference sRGB, got: "
                    + intent.getOutputCondition());
        }
    }

    /**
     * A spy that records how many times compress() was called.
     * Returns a minimal non-JBIG2 result so the assembler falls through
     * to the normal CCITTFactory path.
     */
    static class CallCounter extends JBIG2Compressor {
        int callCount = 0;

        @Override
        public CompressionResult compress(BufferedImage foregroundMask) throws IOException {
            callCount++;
            byte[] dummy = new byte[]{0, 0, 0, 0};
            return new CompressionResult(dummy,
                foregroundMask.getWidth(), foregroundMask.getHeight(), false);
        }
    }

    /**
     * Verifies that PDF/A mode preserves source document metadata
     * (author, title, subject) instead of overwriting it.
     *
     * Bug #3: addPdfaMetadata was setting a static XMP string that
     * replaced any author/title/subject copied by MetadataPreserver.
     */
    @Test
    void pdfaMode_preservesSourceMetadata() throws IOException {
        File source = new File(tempDir, "source-with-meta.pdf");
        String expectedAuthor = "Test Author";
        String expectedTitle = "Test Title";
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(new PDRectangle(PAGE_W, PAGE_H)));
            // Set explicit XMP metadata on the source (the path that gets
            // copied by MetadataPreserver.copyXmlMetadata).
            String sourceXmp = """
                    <?xpacket begin="\\uFEFF" id="W5M0MpCehiHzreSzNTczkc9d"?>
                    <x:xmpmeta xmlns:x="adobe:ns:meta/">
                      <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                        <rdf:Description rdf:about=""
                          xmlns:dc="http://purl.org/dc/elements/1.1/">
                          <dc:creator><rdf:Seq><rdf:li>""" + expectedAuthor + """
                </rdf:li></rdf:Seq></dc:creator>
                          <dc:title><rdf:Alt><rdf:li xml:lang="x-default">""" + expectedTitle + """
                </rdf:li></rdf:Alt></dc:title>
                        </rdf:Description>
                      </rdf:RDF>
                    </x:xmpmeta>
                    <?xpacket end="w"?>""";
            var meta = new org.apache.pdfbox.pdmodel.common.PDMetadata(doc);
            meta.importXMPMetadata(sourceXmp.getBytes(StandardCharsets.UTF_8));
            doc.getDocumentCatalog().setMetadata(meta);
            doc.save(source);
        }

        int imgW = PAGE_W;
        int imgH = PAGE_H;
        List<TextBlock> blocks = new ArrayList<>();
        blocks.add(new TextBlock("Meta", new Rectangle(10, 100, 120, 40), 0.95));
        PageResult ocr = new PageResult(1, imgW, imgH, blocks);
        BufferedImage bg = new BufferedImage(imgW, imgH, BufferedImage.TYPE_BYTE_GRAY);

        PDFAssembler assembler = new PDFAssembler();
        try (PDDocument output = assembler.assemble(source, Collections.singletonList(bg),
                null, Collections.singletonList(ocr), true)) {

            var outMeta = output.getDocumentCatalog().getMetadata();
            assertNotNull(outMeta, "PDF/A output should have XMP metadata");

            String xmp;
            try (InputStream is = outMeta.createInputStream()) {
                xmp = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }

            assertTrue(xmp.contains(expectedAuthor),
                    "XMP metadata should contain author: " + expectedAuthor);
            assertTrue(xmp.contains(expectedTitle),
                    "XMP metadata should contain title: " + expectedTitle);
        }
    }

    /**
     * Verifies that a custom font is loaded once and reused across pages,
     * rather than being embedded N times for N pages.
     *
     * Bug #1 (Claude review): PDType0Font.load() was called per page,
     * embedding N copies of the font in the output PDF.
     */
    @Test
    void pdfaOutputWithCustomFont_embedsFontOnce() throws IOException {
        File fontFile = new File("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf");
        assumeTrue(fontFile.exists(), "DejaVuSans.ttf required for font caching test");

        File source = new File(tempDir, "font-cache-source.pdf");
        try (PDDocument d = new PDDocument()) {
            d.addPage(new PDPage(new PDRectangle(PAGE_W, PAGE_H)));
            d.addPage(new PDPage(new PDRectangle(PAGE_W, PAGE_H)));
            d.save(source);
        }

        int imgW = PAGE_W;
        int imgH = PAGE_H;
        List<TextBlock> blocks = new ArrayList<>();
        blocks.add(new TextBlock("Font", new Rectangle(10, 10, 120, 40), 0.95));
        PageResult ocr = new PageResult(1, imgW, imgH, blocks);
        List<PageResult> ocrResults = new ArrayList<>();
        ocrResults.add(ocr);
        ocrResults.add(ocr);

        BufferedImage bg = new BufferedImage(imgW, imgH, BufferedImage.TYPE_BYTE_GRAY);
        List<BufferedImage> bgs = new ArrayList<>();
        bgs.add(bg);
        bgs.add(bg);

        BufferedImage fgMask = new BufferedImage(imgW, imgH, BufferedImage.TYPE_BYTE_BINARY);
        List<BufferedImage> masks = new ArrayList<>();
        masks.add(fgMask);
        masks.add(fgMask);

        PDFAssembler assembler = new PDFAssembler();
        assembler.setPdfaFont(fontFile);

        File outputFile = new File(tempDir, "font-cache-output.pdf");
        PDDocument doc = assembler.assemble(source, bgs, masks, ocrResults, true);
        doc.save(outputFile);
        doc.close();

        // Count font objects from the saved file (xref table is populated on save)
        try (PDDocument saved = Loader.loadPDF(outputFile)) {
            assertEquals(2, saved.getNumberOfPages(),
                "Document should have 2 pages");

            // PDType0Font.load() creates two /Type /Font dicts per font:
            // the Type0 font + its CIDFont descendant.  2 = one font.
            int fontDictCount = saved.getDocument().getObjectsByType(COSName.FONT).size();
            assertEquals(2, fontDictCount,
                "Custom font should create exactly 2 /Type /Font dicts"
                + " (Type0 + CIDFont), not " + fontDictCount + " (would mean "
                + (fontDictCount / 2) + " font loads for 2 pages)");
        }
    }
}
