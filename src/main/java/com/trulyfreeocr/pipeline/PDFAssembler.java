package com.trulyfreeocr.pipeline;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent;
import org.apache.pdfbox.pdmodel.graphics.image.CCITTFactory;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.util.Matrix;

import com.trulyfreeocr.model.PageResult;
import com.trulyfreeocr.model.TextBlock;
import com.trulyfreeocr.util.Settings;

/**
 * Re-assembles a searchable PDF from per-page inputs:
 *   - Source PDF (copied for page dimensions / media boxes)
 *   - Cleaned background images
 *   - Binary foreground masks (CCITT G4 compressed stencil overlay)
 *   - OCR results (invisible text layer)
 *
 * MRC-like layout (per page):
 *   1. Background layer: cleaned page image (Lossless PNG for now).
 *   2. Foreground mask: CCITT G4 compressed binary mask, drawn as a
 *      PDF ImageMask stencil in black — reveals only the text pixels
 *      on top of the background.
 *   3. Text layer: invisible OCR text (RenderingMode.NEITHER = Tr 3),
 *      selectable and searchable but not visible.
 *
 * Rendering mode NEITHER makes text invisible on screen/print while
 * keeping it selectable and searchable.
 *
 * Coordinate transformation:
 *   Page images at 300 DPI → PDF user space at 72 DPI.
 *   scaleX = pageWidth_pts  / imageWidth_px
 *   scaleY = pageHeight_pts / imageHeight_px
 *   Y is flipped: image origin is top-left, PDF origin is bottom-left.
 */
public class PDFAssembler {

    private final PDType1Font font;
    private final float minFontSize;
    private final MetadataPreserver preserver = new MetadataPreserver();
    private File pdfaFontFile;
    private JBIG2Compressor compressor;

    public PDFAssembler() {
        this("HELVETICA", 1f);
    }

    public PDFAssembler(String fontName, float minFontSize) {
        Standard14Fonts.FontName resolved;
        try {
            resolved = Standard14Fonts.FontName.valueOf(fontName);
        } catch (IllegalArgumentException e) {
            resolved = Standard14Fonts.FontName.HELVETICA;
        }
        this.font = new PDType1Font(resolved);
        this.minFontSize = minFontSize;
    }

    public void setPdfaFont(File fontFile) {
        this.pdfaFontFile = fontFile;
    }

    public void setCompressor(JBIG2Compressor compressor) {
        this.compressor = compressor;
    }

    /**
     * Builds a searchable PDF with MRC-like foreground mask overlay.
     *
     * When foregroundMasks is provided, each mask is CCITT G4 compressed
     * and drawn as a PDF ImageMask stencil in black on top of the background.
     * This reproduces the original text pixels at full sharpness, while
     * the background layer carries the cleaned (de-speckled) page image.
     *
     * @param sourcePdf       Original PDF (for per-page media boxes).
     * @param backgrounds     Per-page cleaned background images.
     * @param foregroundMasks Per-page binary foreground masks (TYPE_BYTE_BINARY,
     *                        black=text, white=background), or null to skip.
     * @param ocrResults      Per-page OCR results.
     * @param usePdfa         Enable PDF/A-2b output with XMP metadata.
     * @return A new PDDocument with foreground mask overlay and searchable text.
     */
    public PDDocument assemble(File sourcePdf,
                               List<BufferedImage> backgrounds,
                               List<BufferedImage> foregroundMasks,
                               List<PageResult> ocrResults,
                               boolean usePdfa) throws IOException {
        PDDocument output = new PDDocument();
        List<PDPage> outPages = new java.util.ArrayList<>();

        try (PDDocument source = Loader.loadPDF(sourcePdf)) {
            int pageCount = source.getNumberOfPages();
            for (int i = 0; i < pageCount; i++) {
                BufferedImage bg = backgrounds.get(i);
                BufferedImage fg = foregroundMasks != null ? foregroundMasks.get(i) : null;
                PDPage page = addPage(output, source, i, bg, fg, ocrResults.get(i));
                outPages.add(page);
            }
            finishAssembly(output, source, outPages, usePdfa);
        }

        return output;
    }

    /**
     * Renders one page into the output document.  Call repeatedly for
     * each page of the source, then call {@link #finishAssembly}.
     *
     * @param output        The output PDDocument being built.
     * @param source        The source PDDocument (already loaded).
     * @param pageIndex     0-based page index in source.
     * @param background    Cleaned background image for this page.
     * @param foregroundMask Binary mask or null to skip the stencil layer.
     * @param ocr           OCR result for this page.
     * @return The newly created PDPage added to output.
     */
    public PDPage addPage(PDDocument output, PDDocument source, int pageIndex,
                          BufferedImage background, BufferedImage foregroundMask,
                          PageResult ocr) throws IOException {
        PDPage sourcePage = source.getPage(pageIndex);
        PDRectangle mediaBox = sourcePage.getMediaBox();
        float pageW = mediaBox.getWidth();
        float pageH = mediaBox.getHeight();

        PDPage outPage = new PDPage(mediaBox);
        output.addPage(outPage);

        try (PDPageContentStream cs = new PDPageContentStream(output, outPage)) {
            // Layer 1: background image
            // When a foreground mask is present, the background can be lossy JPEG
            // because the foreground stencil preserves text pixels at full sharpness.
            // When no mask exists, the background is the only visual layer, so JPEG
            // at a moderate quality is used directly.
            PDImageXObject bgXObject;
            if (foregroundMask == null) {
                bgXObject = JPEGFactory.createFromImage(output, background, 0.85f);
            } else {
                // Background with foreground mask can use lower quality JPEG because
                // text pixels come from the sharp binary stencil layer, not the background.
                bgXObject = JPEGFactory.createFromImage(output, background, 0.70f);
            }
            cs.drawImage(bgXObject, 0, 0, pageW, pageH);

            // Layer 2: foreground mask as CCITT G4 stencil overlay (JBIG2 when available)
            if (foregroundMask != null) {
                PDImageXObject fgImage;
                if (compressor != null) {
                    JBIG2Compressor.CompressionResult result = compressor.compress(foregroundMask);
                    if (result.isJbig2()) {
                        fgImage = createJbig2ImageXObject(output, result);
                    } else {
                        fgImage = CCITTFactory.createFromImage(output, foregroundMask);
                    }
                } else {
                    fgImage = CCITTFactory.createFromImage(output, foregroundMask);
                }
                fgImage.getCOSObject().setBoolean(COSName.IMAGE_MASK, true);
                fgImage.getCOSObject().removeItem(COSName.COLORSPACE);
                cs.setNonStrokingColor(0f, 0f, 0f);
                cs.drawImage(fgImage, 0, 0, pageW, pageH);
            }

            // Layer 3: invisible OCR text
            float scaleX = pageW / ocr.getWidth();
            float scaleY = pageH / ocr.getHeight();
            PDFont pageFont = (pdfaFontFile != null && pdfaFontFile.exists())
                    ? PDType0Font.load(output, pdfaFontFile)
                    : font;
            cs.beginText();
            cs.setFont(pageFont, minFontSize);
            cs.setRenderingMode(RenderingMode.NEITHER);

            for (TextBlock tb : ocr.getTextBlocks()) {
                float x = tb.getBbox().x * scaleX;
                float y = pageH - (tb.getBbox().y + tb.getBbox().height) * scaleY;
                float fontSize = Math.max(tb.getBbox().height * scaleY, minFontSize);
                cs.setFont(pageFont, fontSize);
                cs.setTextMatrix(Matrix.getTranslateInstance(x, y));
                cs.showText(tb.getWord());
            }
            cs.endText();
        }

        return outPage;
    }

    /**
     * Copies metadata from the source and optionally adds PDF/A-2b info.
     * Must be called after all {@link #addPage} calls are complete.
     */
    public void finishAssembly(PDDocument output, PDDocument source,
                               List<PDPage> outPages, boolean usePdfa) throws IOException {
        preserver.preserve(source, output, outPages);
        if (usePdfa) {
            addPdfaMetadata(output);
        }
    }

    private PDImageXObject createJbig2ImageXObject(PDDocument doc, JBIG2Compressor.CompressionResult result) throws IOException {
        PDImageXObject img = new PDImageXObject(doc);
        img.setWidth(result.getWidth());
        img.setHeight(result.getHeight());
        img.setBitsPerComponent(1);
        img.setStencil(true);
        try (OutputStream os = img.getStream().createOutputStream()) {
            os.write(result.getData());
        }
        img.getCOSObject().setItem(COSName.FILTER, COSName.JBIG2_DECODE);
        return img;
    }

    private PDImageXObject createJbig2ImageXObject(PDDocument doc,
                                                    byte[] combinedData,
                                                    int width,
                                                    int height) throws IOException {
        PDImageXObject img = new PDImageXObject(doc);
        img.setWidth(width);
        img.setHeight(height);
        img.setBitsPerComponent(1);
        img.setStencil(true);
        try (OutputStream os = img.getStream().createOutputStream()) {
            os.write(combinedData);
        }
        img.getCOSObject().setItem(COSName.FILTER, COSName.JBIG2_DECODE);
        return img;
    }

    /**
     * Adds one page to the output document using pre-compressed JBIG2
     * foreground data with a shared global symbol dictionary.
     */
    public PDPage addPageJbig2(PDDocument output, PDDocument source, int pageIndex,
                               BufferedImage background,
                               byte[] jbig2PageData, byte[] jbig2GlobalSym,
                               int fgWidth, int fgHeight,
                               PageResult ocr) throws IOException {
        PDPage sourcePage = source.getPage(pageIndex);
        PDRectangle mediaBox = sourcePage.getMediaBox();
        float pageW = mediaBox.getWidth();
        float pageH = mediaBox.getHeight();

        PDPage outPage = new PDPage(mediaBox);
        output.addPage(outPage);

        try (PDPageContentStream cs = new PDPageContentStream(output, outPage)) {
            // Layer 1: background image — lower quality JPEG is safe because the JBIG2
            // foreground mask preserves text pixels at full sharpness.
            PDImageXObject bgXObject = JPEGFactory.createFromImage(output, background, 0.70f);
            cs.drawImage(bgXObject, 0, 0, pageW, pageH);

            // Layer 2: JBIG2 foreground mask — combine globals into page stream
            // to avoid poppler JBIG2 decoder issues with separate /JBIG2Globals.
            if (jbig2PageData != null && jbig2GlobalSym != null) {
                byte[] combined = new byte[jbig2GlobalSym.length + jbig2PageData.length];
                System.arraycopy(jbig2GlobalSym, 0, combined, 0, jbig2GlobalSym.length);
                System.arraycopy(jbig2PageData, 0, combined, jbig2GlobalSym.length, jbig2PageData.length);
                PDImageXObject fgImage = createJbig2ImageXObject(output,
                    combined, fgWidth, fgHeight);
                fgImage.getCOSObject().setBoolean(COSName.IMAGE_MASK, true);
                fgImage.getCOSObject().removeItem(COSName.COLORSPACE);
                cs.setNonStrokingColor(0f, 0f, 0f);
                cs.drawImage(fgImage, 0, 0, pageW, pageH);
            }

            // Layer 3: invisible OCR text
            float scaleX = pageW / ocr.getWidth();
            float scaleY = pageH / ocr.getHeight();
            PDFont pageFont = (pdfaFontFile != null && pdfaFontFile.exists())
                    ? PDType0Font.load(output, pdfaFontFile)
                    : font;
            cs.beginText();
            cs.setFont(pageFont, minFontSize);
            cs.setRenderingMode(RenderingMode.NEITHER);

            for (TextBlock tb : ocr.getTextBlocks()) {
                float x = tb.getBbox().x * scaleX;
                float y = pageH - (tb.getBbox().y + tb.getBbox().height) * scaleY;
                float fontSize = Math.max(tb.getBbox().height * scaleY, minFontSize);
                cs.setFont(pageFont, fontSize);
                cs.setTextMatrix(Matrix.getTranslateInstance(x, y));
                cs.showText(tb.getWord());
            }
            cs.endText();
        }

        return outPage;
    }

    private void addPdfaMetadata(PDDocument doc) throws IOException {
        PDDocumentCatalog catalog = doc.getDocumentCatalog();

        // PDF/A-2b XMP metadata
        String xmp = """
                <?xpacket begin="\\uFEFF" id="W5M0MpCehiHzreSzNTczkc9d"?>
                <x:xmpmeta xmlns:x="adobe:ns:meta/">
                  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                    <rdf:Description rdf:about=""
                      xmlns:pdfaid="http://www.aiim.org/pdfa/ns/id/">
                      <pdfaid:part>2</pdfaid:part>
                      <pdfaid:conformance>B</pdfaid:conformance>
                    </rdf:Description>
                    <rdf:Description rdf:about=""
                      xmlns:dc="http://purl.org/dc/elements/1.1/">
                      <dc:format>application/pdf</dc:format>
                    </rdf:Description>
                  </rdf:RDF>
                </x:xmpmeta>
                <?xpacket end="w"?>""".stripIndent();

        org.apache.pdfbox.pdmodel.common.PDMetadata metadata =
                new org.apache.pdfbox.pdmodel.common.PDMetadata(doc);
        metadata.importXMPMetadata(xmp.getBytes(StandardCharsets.UTF_8));
        catalog.setMetadata(metadata);

        // sRGB output intent
        try (InputStream srgbStream = getClass().getResourceAsStream("/sRGB Color Space Profile.icm")) {
            if (srgbStream != null) {
                PDOutputIntent intent = new PDOutputIntent(doc, srgbStream);
                intent.setInfo("sRGB IEC61966-2.1");
                intent.setOutputCondition("sRGB IEC61966-2.1");
                intent.setOutputConditionIdentifier("sRGB IEC61966-2.1");
                intent.setRegistryName("http://www.color.org");
                catalog.addOutputIntent(intent);
            }
        }
    }

}
