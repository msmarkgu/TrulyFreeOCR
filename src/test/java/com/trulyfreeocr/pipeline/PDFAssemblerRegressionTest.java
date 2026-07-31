package com.trulyfreeocr.pipeline;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.trulyfreeocr.model.PageResult;
import com.trulyfreeocr.model.TextBlock;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PDFAssemblerRegressionTest {

    File tempDir;

    @BeforeEach
    void setup() throws IOException {
        Files.createDirectories(Path.of("temp"));
        tempDir = Files.createTempDirectory(Path.of("temp"), "tfocr-regression-").toFile();
    }

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
     * Regression test for the JBIG2-globals stream caching.
     *
     * The pre-fix code concatenated the global symbol dictionary into each
     * page's stream, embedding N identical copies in the PDF.  The fix stores
     * the global dictionary as a separate /JBIG2Globals stream and references
     * it via DecodeParms, sharing one copy across all pages.
     *
     * This test assembles two JBIG2 pages and verifies:
     * 1. Each page references a /JBIG2Globals stream via DecodeParms.
     * 2. Both pages reference the same COSStream instance (the cache hit).
     * 3. The image stream contains only page data (no globals prepended).
     */
    @Test
    void addPageJbig2_usesSharedGlobalsReference() throws Exception {
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

            // Verify each page references the shared /JBIG2Globals stream via DecodeParms
            COSBase firstGlobals = null;
            for (int i = 0; i < 2; i++) {
                PDPage page = doc.getPage(i);
                boolean foundJbig2 = false;
                for (COSName name : page.getResources().getXObjectNames()) {
                    PDXObject xobj = page.getResources().getXObject(name);
                    if (xobj instanceof PDImageXObject) {
                        PDImageXObject ximg = (PDImageXObject) xobj;
                        COSBase filter = ximg.getCOSObject().getItem(COSName.FILTER);
                        if (COSName.JBIG2_DECODE.equals(filter)) {
                            // Verify /JBIG2Globals is set inside DecodeParms
                            COSDictionary decodeParms = (COSDictionary)
                                ximg.getCOSObject().getItem(COSName.DECODE_PARMS);
                            assertNotNull(decodeParms,
                                "Page " + i + " should have DecodeParms");
                            COSBase globalsRef = decodeParms.getItem(COSName.JBIG2_GLOBALS);
                            assertNotNull(globalsRef,
                                "Page " + i + " should reference a separate JBIG2Globals stream");
                            if (firstGlobals == null) {
                                firstGlobals = globalsRef;
                            } else {
                                assertSame(firstGlobals, globalsRef,
                                    "Both pages should reference the same JBIG2Globals stream");
                            }
                            // Verify stream contains only page data (no globals)
                            assertEquals(ximg.getCOSObject().getLength(), pageData.length,
                                "Page " + i + " JBIG2 stream should contain only page data");
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
        assembler.setFont(fontFile);

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

    @Test
    void producer_appendsToSourceProducer() throws IOException {
        File source = new File(tempDir, "source-with-producer.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(new PDRectangle(PAGE_W, PAGE_H)));
            doc.getDocumentInformation().setProducer("SourceTool");
            doc.save(source);
        }

        BufferedImage bg = new BufferedImage(PAGE_W, PAGE_H, BufferedImage.TYPE_BYTE_GRAY);
        List<TextBlock> blocks = new ArrayList<>();
        blocks.add(new TextBlock("x", new Rectangle(10, 10, 20, 20), 0.95));
        PageResult ocr = new PageResult(1, PAGE_W, PAGE_H, blocks);

        PDFAssembler assembler = new PDFAssembler();
        assembler.setProducer("TrulyFreeOCR");
        try (PDDocument output = assembler.assemble(source,
                Collections.singletonList(bg), null, Collections.singletonList(ocr), false)) {
            assertEquals("SourceTool -> TrulyFreeOCR",
                output.getDocumentInformation().getProducer());
        }
    }

    @Test
    void producer_setsWhenSourceMissing() throws IOException {
        File source = new File(tempDir, "source-no-producer.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(new PDRectangle(PAGE_W, PAGE_H)));
            // Intentionally no setProducer — source has no Producer
            doc.save(source);
        }

        BufferedImage bg = new BufferedImage(PAGE_W, PAGE_H, BufferedImage.TYPE_BYTE_GRAY);
        List<TextBlock> blocks = new ArrayList<>();
        blocks.add(new TextBlock("x", new Rectangle(10, 10, 20, 20), 0.95));
        PageResult ocr = new PageResult(1, PAGE_W, PAGE_H, blocks);

        PDFAssembler assembler = new PDFAssembler();
        assembler.setProducer("TrulyFreeOCR");
        try (PDDocument output = assembler.assemble(source,
                Collections.singletonList(bg), null, Collections.singletonList(ocr), false)) {
            assertEquals("TrulyFreeOCR",
                output.getDocumentInformation().getProducer());
        }
    }

    /**
     * Verifies that the assembler does not crash when TextBlocks contain
     * characters not supported by the default Helvetica font (e.g. CJK).
     * The try-catch fallback should estimate width and skip unsupported glyphs.
     */
    @Test
    void addPage_nonLatinGlyphs_doesNotThrow() throws IOException {
        File source = createSourcePdf();
        int imgW = PAGE_W;
        int imgH = PAGE_H;

        List<TextBlock> blocks = new ArrayList<>();
        blocks.add(new TextBlock("Hello", new Rectangle(10, 100, 120, 40), 0.95));
        blocks.add(new TextBlock("\u4f60\u597d\u4e16\u754c", new Rectangle(10, 200, 150, 40), 0.95));
        blocks.add(new TextBlock("\u3053\u3093\u306b\u3061\u306f", new Rectangle(10, 300, 140, 40), 0.95));
        PageResult ocr = new PageResult(1, imgW, imgH, blocks);

        BufferedImage bg = new BufferedImage(imgW, imgH, BufferedImage.TYPE_BYTE_GRAY);

        PDFAssembler assembler = new PDFAssembler();
        try (PDDocument doc = assembler.assemble(source,
                Collections.singletonList(bg), null,
                Collections.singletonList(ocr), false)) {
            assertNotNull(doc, "Assembled document should not be null");
            assertEquals(1, doc.getNumberOfPages());
        }
    }

    // ── MRC Implementation Tests ─────────────────────────────────────────

    @Test
    void mrcBackground_usesJpegEncoding() throws IOException {
        File source = createSourcePdf();
        int imgW = PAGE_W;
        int imgH = PAGE_H;

        List<TextBlock> blocks = Collections.singletonList(
            new TextBlock("Test", new Rectangle(10, 10, 100, 40), 0.95));
        PageResult ocr = new PageResult(1, imgW, imgH, blocks);
        BufferedImage bg = new BufferedImage(imgW, imgH, BufferedImage.TYPE_BYTE_GRAY);
        BufferedImage fgMask = new BufferedImage(imgW, imgH, BufferedImage.TYPE_BYTE_BINARY);
        List<BufferedImage> masks = Collections.singletonList(fgMask);

        PDFAssembler assembler = new PDFAssembler();
        try (PDDocument doc = assembler.assemble(source,
                Collections.singletonList(bg), masks,
                Collections.singletonList(ocr), false)) {
            PDPage page = doc.getPage(0);
            boolean foundBg = false;
            for (COSName name : page.getResources().getXObjectNames()) {
                PDXObject xobj = page.getResources().getXObject(name);
                if (xobj instanceof PDImageXObject img) {
                    if (!img.getCOSObject().getBoolean(COSName.IMAGE_MASK, false)) {
                        assertEquals(COSName.DCT_DECODE,
                            img.getCOSObject().getItem(COSName.FILTER),
                            "Background image should use DCTDecode (JPEG) filter");
                        foundBg = true;
                    }
                }
            }
            assertTrue(foundBg, "No background XObject found");
        }
    }

    @Test
    void mrcBackground_isDownscaled() throws IOException {
        File source = createSourcePdf();
        int imgW = PAGE_W;
        int imgH = PAGE_H;

        List<TextBlock> blocks = Collections.singletonList(
            new TextBlock("Test", new Rectangle(10, 10, 100, 40), 0.95));
        PageResult ocr = new PageResult(1, imgW, imgH, blocks);
        BufferedImage bg = new BufferedImage(imgW, imgH, BufferedImage.TYPE_BYTE_GRAY);
        BufferedImage fgMask = new BufferedImage(imgW, imgH, BufferedImage.TYPE_BYTE_BINARY);
        List<BufferedImage> masks = Collections.singletonList(fgMask);

        PDFAssembler assembler = new PDFAssembler();
        assembler.setBackgroundScale(0.5);
        try (PDDocument doc = assembler.assemble(source,
                Collections.singletonList(bg), masks,
                Collections.singletonList(ocr), false)) {
            PDPage page = doc.getPage(0);
            for (COSName name : page.getResources().getXObjectNames()) {
                PDXObject xobj = page.getResources().getXObject(name);
                if (xobj instanceof PDImageXObject img) {
                    if (!img.getCOSObject().getBoolean(COSName.IMAGE_MASK, false)) {
                        int expectedW = (int) Math.round(imgW * 0.5);
                        int expectedH = (int) Math.round(imgH * 0.5);
                        assertEquals(expectedW, img.getWidth(),
                            "Background width should be scaled by 0.5");
                        assertEquals(expectedH, img.getHeight(),
                            "Background height should be scaled by 0.5");
                        return;
                    }
                }
            }
            fail("No background XObject found");
        }
    }

    @Test
    void mrcBackground_chromaIs420() throws IOException {
        // Assemble a simple MRC document, save, reload, and extract the
        // raw JPEG bytes from the background XObject to verify 4:2:0.
        File source = createSourcePdf();
        int imgW = PAGE_W;
        int imgH = PAGE_H;

        List<TextBlock> blocks = Collections.singletonList(
            new TextBlock("Test", new Rectangle(10, 10, 100, 40), 0.95));
        PageResult ocr = new PageResult(1, imgW, imgH, blocks);
        BufferedImage bg = new BufferedImage(imgW, imgH, BufferedImage.TYPE_3BYTE_BGR);
        BufferedImage fgMask = new BufferedImage(imgW, imgH, BufferedImage.TYPE_BYTE_BINARY);
        List<BufferedImage> masks = Collections.singletonList(fgMask);

        File mrcFile = new File(tempDir, "mrc-chroma-test.pdf");
        PDFAssembler assembler = new PDFAssembler();
        try (PDDocument doc = assembler.assemble(source,
                Collections.singletonList(bg), masks,
                Collections.singletonList(ocr), false)) {
            doc.save(mrcFile);
        }

        // Reload the saved PDF
        try (PDDocument doc = Loader.loadPDF(mrcFile)) {
            PDPage page = doc.getPage(0);
            for (COSName name : page.getResources().getXObjectNames()) {
                PDXObject xobj = page.getResources().getXObject(name);
                if (xobj instanceof PDImageXObject img) {
                    if (!img.getCOSObject().getBoolean(COSName.IMAGE_MASK, false)) {
                        // Read the raw COS stream bytes (these are JPEG data)
                        byte[] jpegBytes;
                        try (InputStream is = ((org.apache.pdfbox.cos.COSStream) img.getCOSObject()).createRawInputStream()) {
                            jpegBytes = is.readAllBytes();
                        }
                        assertTrue(jpegBytes.length > 2,
                            "JPEG data should be at least 2 bytes");
                        assertEquals(0xFF, jpegBytes[0] & 0xFF,
                            "JPEG should start with 0xFF");
                        assertEquals(0xD8, jpegBytes[1] & 0xFF,
                            "JPEG should start with SOI marker 0xD8");

                        // Parse the JPEG to find SOF0 marker and subsampling
                        int pos = 2;
                        while (pos < jpegBytes.length) {
                            if ((jpegBytes[pos] & 0xFF) == 0xFF) {
                                int marker = jpegBytes[pos + 1] & 0xFF;
                                if (marker == 0xC0 || marker == 0xC1 || marker == 0xC2) {
                                    // SOF marker: skip segment length (2) + precision (1) + height (2) + width (2)
                                    int numComponents = jpegBytes[pos + 9] & 0xFF;
                                    int compPos = pos + 10;
                                    boolean lumaOk = false, chromaOk = false;
                                    for (int ci = 0; ci < numComponents; ci++) {
                                        int compId = jpegBytes[compPos] & 0xFF;
                                        int hSample = (jpegBytes[compPos + 1] >> 4) & 0x0F;
                                        int vSample = jpegBytes[compPos + 1] & 0x0F;
                                        if (compId == 0 || compId == 1) {
                                            // First component is Y (luma)
                                            assertEquals(2, hSample,
                                                "Luma H-sampling should be 2");
                                            assertEquals(2, vSample,
                                                "Luma V-sampling should be 2");
                                            lumaOk = true;
                                        } else {
                                            assertEquals(1, hSample,
                                                "Chroma H-sampling should be 1");
                                            assertEquals(1, vSample,
                                                "Chroma V-sampling should be 1");
                                            chromaOk = true;
                                        }
                                        compPos += 3;
                                    }
                                    assertTrue(lumaOk, "Luma component not found in SOF");
                                    assertTrue(chromaOk, "Chroma components not found");
                                    return;
                                }
                                if (marker == 0xD9) break; // EOI
                                // Skip non-SOF marker: length at pos+2 (big-endian)
                                int segLen = ((jpegBytes[pos + 2] & 0xFF) << 8)
                                           | (jpegBytes[pos + 3] & 0xFF);
                                pos += 2 + segLen;
                            } else {
                                pos++;
                            }
                        }
                        fail("SOF marker not found in JPEG data");
                    }
                }
            }
            fail("No background XObject found");
        }
    }

    @Test
    void mrcCompression_reducesFileSize() throws IOException {
        // Use a multi-page source for measurable MRC savings
        File pdf = new File(tempDir, "mrc-compare-source.pdf");
        try (PDDocument d = new PDDocument()) {
            for (int i = 0; i < 3; i++) {
                d.addPage(new PDPage(new PDRectangle(PAGE_W, PAGE_H)));
            }
            d.save(pdf);
        }

        int imgW = PAGE_W;
        int imgH = PAGE_H;
        List<TextBlock> blocks = Collections.singletonList(
            new TextBlock("Test", new Rectangle(10, 10, 100, 40), 0.95));
        PageResult ocr = new PageResult(1, imgW, imgH, blocks);
        List<PageResult> ocrResults = new ArrayList<>();
        ocrResults.add(ocr); ocrResults.add(ocr); ocrResults.add(ocr);

        BufferedImage bg = new BufferedImage(imgW, imgH, BufferedImage.TYPE_BYTE_GRAY);
        List<BufferedImage> bgs = new ArrayList<>();
        BufferedImage fgMask = new BufferedImage(imgW, imgH, BufferedImage.TYPE_BYTE_BINARY);
        List<BufferedImage> masks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            bgs.add(bg);
            masks.add(fgMask);
        }

        // MRC mode: with foreground mask + background scaling
        PDFAssembler mrcAssembler = new PDFAssembler();
        mrcAssembler.setBackgroundScale(0.33);
        File mrcFile = new File(tempDir, "mrc-output.pdf");
        try (PDDocument doc = mrcAssembler.assemble(pdf, bgs, masks, ocrResults, false)) {
            doc.save(mrcFile);
        }

        // Non-MRC mode: no foreground mask
        PDFAssembler plainAssembler = new PDFAssembler();
        File plainFile = new File(tempDir, "plain-output.pdf");
        try (PDDocument doc = plainAssembler.assemble(pdf, bgs, null, ocrResults, false)) {
            doc.save(plainFile);
        }

        assertTrue(mrcFile.exists() && mrcFile.length() > 0, "MRC output should exist");
        assertTrue(plainFile.exists() && plainFile.length() > 0, "Plain output should exist");
        assertTrue(mrcFile.length() < plainFile.length(),
            "MRC output (" + mrcFile.length() + " bytes) should be smaller than non-MRC ("
            + plainFile.length() + " bytes)");
    }

    @Test
    void mrcForegroundMask_preservesTextSharpness() throws IOException {
        int imgW = 200;
        int imgH = 100;

        // Background: entirely white, so lossy JPEG can't introduce dark pixels
        BufferedImage bg = new BufferedImage(imgW, imgH, BufferedImage.TYPE_3BYTE_BGR);
        java.awt.Graphics2D g = bg.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, imgW, imgH);
        g.dispose();

        // Foreground mask: white background (transparent) + black rectangle (opaque)
        BufferedImage fgMask = new BufferedImage(imgW, imgH, BufferedImage.TYPE_BYTE_BINARY);
        java.awt.Graphics2D mg = fgMask.createGraphics();
        mg.setColor(java.awt.Color.WHITE);
        mg.fillRect(0, 0, imgW, imgH);
        mg.setColor(java.awt.Color.BLACK);
        mg.fillRect(20, 20, 60, 30);
        mg.dispose();

        File source = createSourcePdf();
        List<TextBlock> blocks = Collections.singletonList(
            new TextBlock("x", new Rectangle(20, 20, 60, 30), 0.95));
        PageResult ocr = new PageResult(1, imgW, imgH, blocks);

        PDFAssembler assembler = new PDFAssembler();
        assembler.setBackgroundScale(0.33);
        assembler.setBgSmoothSigma(3.0f);

        try (PDDocument doc = assembler.assemble(source,
                Collections.singletonList(bg), Collections.singletonList(fgMask),
                Collections.singletonList(ocr), false)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            // At 72 DPI, rendered image = 400 × 600 px (page is 400×600 pt)
            BufferedImage rendered = renderer.renderImageWithDPI(0, 72);

            // Mask rectangle in image coords (20,20)-(80,50), image is 200×100.
            // PDF page is 400×600, so mask scales: scaleX=2, scaleY=6.
            // PDF coords (bottom-left origin):
            //   x: 20/200*400=40 to 80/200*400=160
            //   y: (100-50)/100*600=300 to (100-20)/100*600=480
            // Rendered image (top-left origin) flips y: 600-480=120 to 600-300=300
            // Pixel (100,200) is well inside that rectangle → should be dark
            int textPixel = rendered.getRGB(100, 200);
            int textR = (textPixel >> 16) & 0xFF;
            assertTrue(textR < 64,
                "Text pixel should be dark (R=" + textR + "), mask should overlay black");

            // Background area far from mask rectangle at (300,100) should be
            // the JPEG-compressed white background, still near-white.
            int bgPixel = rendered.getRGB(300, 100);
            int bgR = (bgPixel >> 16) & 0xFF;
            assertTrue(bgR > 192,
                "Background pixel should be light (R=" + bgR + "), background shows through");
        }
    }

    @Test
    void jbig2_compressesBetterThanCcitt() throws IOException {
        // Probe whether jbig2enc is available
        JBIG2Compressor probe = new JBIG2Compressor();
        BufferedImage probeMask = new BufferedImage(10, 10, BufferedImage.TYPE_BYTE_BINARY);
        probeMask.setRGB(5, 5, 0xFF000000);
        var probeResult = probe.compress(probeMask);
        assumeTrue(probeResult.isJbig2(), "jbig2enc not available — skipping JBIG2 comparison test");

        // Create a 2-page source
        File pdf = new File(tempDir, "jbig2-compare-source.pdf");
        try (PDDocument d = new PDDocument()) {
            d.addPage(new PDPage(new PDRectangle(PAGE_W, PAGE_H)));
            d.addPage(new PDPage(new PDRectangle(PAGE_W, PAGE_H)));
            d.save(pdf);
        }

        int imgW = PAGE_W;
        int imgH = PAGE_H;
        List<TextBlock> blocks = new ArrayList<>();
        // Same word on both pages so JBIG2 symbol reuse helps
        blocks.add(new TextBlock("Test", new Rectangle(10, 10, 100, 40), 0.95));
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

        // CCITT G4 path (no compressor)
        PDFAssembler ccittAssembler = new PDFAssembler();
        File ccittFile = new File(tempDir, "ccitt-output.pdf");
        try (PDDocument doc = ccittAssembler.assemble(pdf, bgs, masks, ocrResults, false)) {
            doc.save(ccittFile);
        }

        // JBIG2 path (with shared compressor)
        PDFAssembler jbig2Assembler = new PDFAssembler();
        jbig2Assembler.setCompressor(new JBIG2Compressor());
        File jbig2File = new File(tempDir, "jbig2-output.pdf");
        try (PDDocument doc = jbig2Assembler.assemble(pdf, bgs, masks, ocrResults, false)) {
            doc.save(jbig2File);
        }

        assertTrue(jbig2File.length() < ccittFile.length(),
            "JBIG2 output (" + jbig2File.length() + " bytes) should be smaller than CCITT ("
            + ccittFile.length() + " bytes) for multi-page with repeated content");
    }
}
