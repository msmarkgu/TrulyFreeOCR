package com.trulyfreeocr.pipeline;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
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
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

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
    private double backgroundScale;
    private float bgSmoothSigma;

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
        this.backgroundScale = 1.0;
        this.bgSmoothSigma = 0f;
    }

    public void setPdfaFont(File fontFile) {
        this.pdfaFontFile = fontFile;
    }

    public void setCompressor(JBIG2Compressor compressor) {
        this.compressor = compressor;
    }

    public void setBackgroundScale(double scale) {
        this.backgroundScale = Math.max(0.1, Math.min(1.0, scale));
    }

    public void setBgSmoothSigma(float sigma) {
        this.bgSmoothSigma = Math.max(0f, sigma);
    }

    private PDImageXObject encodeBackgroundJpeg(PDDocument doc, BufferedImage image, float quality, boolean hasMask) throws IOException {
        BufferedImage toEncode = image;

        // Step 1: Downsample background (text sharpness preserved by mask).
        // Do this FIRST so subsequent Gaussian blur runs at reduced resolution (~9x fewer pixels).
        if (hasMask && backgroundScale < 1.0) {
            int newW = Math.max(1, (int) Math.round(image.getWidth() * backgroundScale));
            int newH = Math.max(1, (int) Math.round(image.getHeight() * backgroundScale));
            BufferedImage scaled = new BufferedImage(newW, newH, BufferedImage.TYPE_3BYTE_BGR);
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(image, 0, 0, newW, newH, null);
            g.dispose();
            toEncode = scaled;
        }

        // Step 2: Pre-encode smoothing (reduces JPEG artifacts, improves compression)
        // Applied at reduced resolution when downsampling is active.
        if (hasMask && bgSmoothSigma > 0f) {
            toEncode = gaussianBlur(toEncode, bgSmoothSigma);
        }

        // Step 3: Encode as JPEG with 4:2:0 chroma subsampling + progressive
        // Using ImageWriter directly instead of JPEGFactory for chroma control
        ImageWriter writer = null;
        try {
            writer = ImageIO.getImageWritersByFormatName("JPEG").next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            param.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);

            ByteArrayOutputStream baos = new ByteArrayOutputStream(8192);
            writer.setOutput(new MemoryCacheImageOutputStream(baos));
            writer.write(null, new IIOImage(toEncode, null, null), param);

            return PDImageXObject.createFromByteArray(doc, baos.toByteArray(), "background");
        } finally {
            if (writer != null) writer.dispose();
        }
    }

    static BufferedImage gaussianBlur(BufferedImage image, float sigma) {
        int radius = (int) Math.ceil(2 * sigma);
        if (radius < 1) return image;

        float[] kernel = new float[2 * radius + 1];
        float sum = 0;
        for (int i = -radius; i <= radius; i++) {
            float v = (float) Math.exp(-(i * i) / (2 * sigma * sigma));
            kernel[i + radius] = v;
            sum += v;
        }
        for (int i = 0; i < kernel.length; i++) kernel[i] /= sum;

        int w = image.getWidth(), h = image.getHeight();
        BufferedImage temp = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);

        // Horizontal pass
        int[] srcRow = new int[w];
        int[] dstRow = new int[w];
        for (int y = 0; y < h; y++) {
            image.getRGB(0, y, w, 1, srcRow, 0, w);
            for (int x = 0; x < w; x++) {
                float r = 0, g = 0, b = 0;
                for (int k = -radius; k <= radius; k++) {
                    int sx = Math.max(0, Math.min(w - 1, x + k));
                    int px = srcRow[sx];
                    float f = kernel[k + radius];
                    r += f * ((px >> 16) & 0xFF);
                    g += f * ((px >> 8) & 0xFF);
                    b += f * (px & 0xFF);
                }
                dstRow[x] = 0xFF000000 | ((int) r << 16) | ((int) g << 8) | (int) b;
            }
            temp.setRGB(0, y, w, 1, dstRow, 0, w);
        }

        // Vertical pass (bulk array)
        int[] pixels = temp.getRGB(0, 0, w, h, null, 0, w);
        int[] outPixels = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float r = 0, g = 0, b = 0;
                for (int k = -radius; k <= radius; k++) {
                    int sy = Math.max(0, Math.min(h - 1, y + k));
                    int px = pixels[sy * w + x];
                    float f = kernel[k + radius];
                    r += f * ((px >> 16) & 0xFF);
                    g += f * ((px >> 8) & 0xFF);
                    b += f * (px & 0xFF);
                }
                outPixels[y * w + x] = 0xFF000000 | ((int) r << 16) | ((int) g << 8) | (int) b;
            }
        }
        result.setRGB(0, 0, w, h, outPixels, 0, w);

        return result;
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
            boolean hasMask = foregroundMask != null;
            float bgQuality = hasMask ? 0.50f : 0.85f;
            PDImageXObject bgXObject = encodeBackgroundJpeg(output, background, bgQuality, hasMask);
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
            PDImageXObject bgXObject = encodeBackgroundJpeg(output, background, 0.50f, true);
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

        // Merge PDF/A identification into existing XMP metadata,
        // preserving source document metadata (author, title, etc.).
        PDMetadata existingMeta = catalog.getMetadata();
        if (existingMeta != null) {
            try {
                byte[] existingBytes;
                try (InputStream is = existingMeta.createInputStream()) {
                    existingBytes = is.readAllBytes();
                }
                String xmpStr = new String(existingBytes, StandardCharsets.UTF_8);

                int xmpStart = xmpStr.indexOf("<x:xmpmeta");
                int xmpEnd = xmpStr.lastIndexOf("</x:xmpmeta>");
                if (xmpStart >= 0 && xmpEnd > xmpStart) {
                    String rawXml = xmpStr.substring(xmpStart, xmpEnd + "</x:xmpmeta>".length());

                    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                    factory.setNamespaceAware(true);
                    DocumentBuilder builder = factory.newDocumentBuilder();
                    Document xmlDoc = builder.parse(
                            new ByteArrayInputStream(rawXml.getBytes(StandardCharsets.UTF_8)));

                    NodeList descList = xmlDoc.getElementsByTagNameNS(
                            "http://www.w3.org/1999/02/22-rdf-syntax-ns#", "Description");
                    if (descList.getLength() > 0) {
                        Element desc = (Element) descList.item(0);
                        desc.setAttributeNS("http://www.w3.org/2000/xmlns/",
                                "xmlns:pdfaid", "http://www.aiim.org/pdfa/ns/id/");

                        NodeList partList = xmlDoc.getElementsByTagNameNS(
                                "http://www.aiim.org/pdfa/ns/id/", "part");
                        if (partList.getLength() == 0) {
                            Element partEl = xmlDoc.createElementNS(
                                    "http://www.aiim.org/pdfa/ns/id/", "pdfaid:part");
                            partEl.setTextContent("2");
                            desc.appendChild(partEl);
                        }

                        NodeList confList = xmlDoc.getElementsByTagNameNS(
                                "http://www.aiim.org/pdfa/ns/id/", "conformance");
                        if (confList.getLength() == 0) {
                            Element confEl = xmlDoc.createElementNS(
                                    "http://www.aiim.org/pdfa/ns/id/", "pdfaid:conformance");
                            confEl.setTextContent("B");
                            desc.appendChild(confEl);
                        }
                    }

                    TransformerFactory tf = TransformerFactory.newInstance();
                    Transformer transformer = tf.newTransformer();
                    transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
                    transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
                    StringWriter sw = new StringWriter();
                    transformer.transform(new DOMSource(xmlDoc), new StreamResult(sw));
                    String modifiedXml = sw.toString();

                    String wrapped = "<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n"
                            + modifiedXml + "\n<?xpacket end=\"w\"?>";

                    PDMetadata metadata = new PDMetadata(doc);
                    metadata.importXMPMetadata(wrapped.getBytes(StandardCharsets.UTF_8));
                    catalog.setMetadata(metadata);
                }
            } catch (Exception e) {
                setStaticPdfaMetadata(catalog, doc);
            }
        } else {
            setStaticPdfaMetadata(catalog, doc);
        }

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

    private void setStaticPdfaMetadata(PDDocumentCatalog catalog, PDDocument doc) throws IOException {
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
        PDMetadata metadata = new PDMetadata(doc);
        metadata.importXMPMetadata(xmp.getBytes(StandardCharsets.UTF_8));
        catalog.setMetadata(metadata);
    }

}
