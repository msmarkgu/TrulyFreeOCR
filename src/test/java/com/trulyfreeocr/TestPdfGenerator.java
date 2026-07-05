package com.trulyfreeocr;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationUnderline;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "generate-test-pdfs",
         description = "Generate test PDFs for unit tests",
         mixinStandardHelpOptions = true)
public class TestPdfGenerator implements Callable<Integer> {

    private static final int W = 612, H = 792;
    private static final int MARGIN = 50;
    private static final int LINE_HEIGHT = 22;
    private static final String FONT_PATH = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf";
    private static final Path OUT_DIR = Path.of("tests", "test-files", "generated");

    @Option(names = "--force", description = "Regenerate existing files")
    private boolean force;

    private Font font14;
    private Font font16;
    private Font font18;

    @Override
    public Integer call() {
        try {
            Files.createDirectories(OUT_DIR);
            font14 = loadFont(14);
            font16 = loadFont(16);
            font18 = loadFont(18);

            makeBlank();
            makeSimpleText();
            makeMultiPage();
            makeTwoColumn();
            makeWithAnnotations();
            makeNoisyScan();

            System.out.println("\nDone. Files in " + OUT_DIR + "/");
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    private Font loadFont(int size) {
        File ttf = new File(FONT_PATH);
        if (ttf.exists()) {
            try {
                return Font.createFont(Font.TRUETYPE_FONT, ttf).deriveFont((float) size);
            } catch (Exception e) { /* fall through */ }
        }
        return new Font("SansSerif", Font.PLAIN, size);
    }

    private BufferedImage newPage() {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.dispose();
        return img;
    }

    private Graphics2D createGraphics(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.BLACK);
        return g;
    }

    private void saveImagePdf(List<BufferedImage> pages, String name) throws IOException {
        Path path = OUT_DIR.resolve(name);
        try (PDDocument doc = new PDDocument()) {
            for (BufferedImage img : pages) {
                PDImageXObject pdImg = JPEGFactory.createFromImage(doc, img, 0.95f);
                PDPage pdPage = new PDPage(new PDRectangle(W, H));
                doc.addPage(pdPage);
                try (PDPageContentStream cs = new PDPageContentStream(doc, pdPage)) {
                    cs.drawImage(pdImg, 0, 0, W, H);
                }
            }
            doc.save(path.toFile());
        }
        System.out.println("  " + name);
    }

    // ── 1. blank.pdf ────────────────────────────────────────────────────

    private void makeBlank() throws IOException {
        saveImagePdf(List.of(newPage()), "blank.pdf");
    }

    // ── 2. simple-text.pdf ──────────────────────────────────────────────

    private void makeSimpleText() throws IOException {
        BufferedImage img = newPage();
        Graphics2D g = createGraphics(img);
        g.setFont(font14);
        String[] lines = {
            "The quick brown fox jumps over the lazy dog.",
            "Pack my box with five dozen liquor jugs.",
            "Sphinx of black quartz, judge my vow.",
            "",
            "This is a test document for OCR validation.",
            "It contains English text rendered as an image,",
            "simulating a scanned page that is not searchable.",
            "",
            "The pipeline should extract this text via Tesseract,",
            "then embed it as invisible text over the image.",
            "The output PDF should be searchable while retaining",
            "the original visual appearance.",
        };
        int y = MARGIN;
        for (String line : lines) {
            if (!line.isEmpty()) g.drawString(line, MARGIN, y);
            y += LINE_HEIGHT;
        }
        g.dispose();
        saveImagePdf(List.of(img), "simple-text.pdf");
    }

    // ── 3. multi-page.pdf ───────────────────────────────────────────────

    private void makeMultiPage() throws IOException {
        String[][] pageData = {
            {
                "Page 1: Introduction",
                "This is the first page of a multi-page document.",
                "It contains introductory text about the topic.",
                "Each page has different content to test that",
                "the OCR pipeline processes all pages correctly.",
            },
            {
                "Page 2: Methodology",
                "The approach uses a 6-step pipeline:",
                "1. Page extraction via PDFBox at 300 DPI",
                "2. Image segmentation via pure-Java binarization",
                "3. JBIG2 compression of text mask",
                "4. OCR via Tesseract CLI subprocess",
                "5. PDF re-assembly with searchable text",
                "6. Metadata preservation (bookmarks, annotations)",
            },
            {
                "Page 3: Results",
                "The output is a searchable, highly-compressed PDF",
                "that maintains the original visual appearance",
                "while adding a hidden text layer for searching.",
                "All metadata from the source PDF is preserved.",
            },
        };

        List<BufferedImage> pages = new ArrayList<>();
        for (String[] data : pageData) {
            BufferedImage img = newPage();
            Graphics2D g = createGraphics(img);
            g.setFont(font18);
            g.drawString(data[0], MARGIN, MARGIN);
            g.setFont(font14);
            int y = MARGIN + 40;
            for (int i = 1; i < data.length; i++) {
                g.drawString(data[i], MARGIN, y);
                y += LINE_HEIGHT;
            }
            g.dispose();
            pages.add(img);
        }
        saveImagePdf(pages, "multi-page.pdf");
    }

    // ── 4. two-column.pdf ───────────────────────────────────────────────

    private void makeTwoColumn() throws IOException {
        BufferedImage img = newPage();
        int colW = (W - 3 * MARGIN) / 2;
        int x1 = MARGIN;
        int x2 = MARGIN * 2 + colW;

        String body = "Lorem ipsum dolor sit amet, consectetur adipiscing "
            + "elit. Sed do eiusmod tempor incididunt ut labore et "
            + "dolore magna aliqua. Ut enim ad minim veniam, quis "
            + "nostrud exercitation ullamco laboris nisi ut aliquip "
            + "ex ea commodo consequat. Duis aute irure dolor in "
            + "reprehenderit in voluptate velit esse cillum dolore "
            + "eu fugiat nulla pariatur. Excepteur sint occaecat "
            + "cupidatat non proident, sunt in culpa qui officia "
            + "deserunt mollit anim id est laborum.";

        String[] words = body.split(" ");
        List<String> wrapped = new ArrayList<>();
        for (int i = 0; i < words.length; i += 8) {
            int end = Math.min(i + 8, words.length);
            wrapped.add(String.join(" ", Arrays.copyOfRange(words, i, end)));
        }

        Graphics2D g = createGraphics(img);
        g.setFont(font16);
        g.drawString("Left Column", x1, MARGIN);
        g.drawString("Right Column", x2, MARGIN);
        g.setFont(font14);
        int y = MARGIN + 30;
        for (String line : wrapped) {
            g.drawString(line, x1, y);
            g.drawString(line, x2, y);
            y += 18;
        }
        g.dispose();
        saveImagePdf(List.of(img), "two-column.pdf");
    }

    // ── 5. with-annotations.pdf ─────────────────────────────────────────

    private void makeWithAnnotations() throws IOException {
        String[] titles = {
            "Chapter 1: Getting Started",
            "Chapter 2: Configuration",
            "Chapter 3: Usage"
        };

        List<BufferedImage> imgs = new ArrayList<>();
        for (int i = 0; i < titles.length; i++) {
            BufferedImage img = newPage();
            Graphics2D g = createGraphics(img);
            g.setFont(font18);
            g.drawString(titles[i], MARGIN, MARGIN);
            g.setFont(font14);
            g.drawString("This is page " + (i + 1) + " of the annotated document.",
                    MARGIN, MARGIN + 40);
            g.drawString("This PDF includes bookmarks and annotations.",
                    MARGIN, MARGIN + 70);
            g.dispose();
            imgs.add(img);
        }

        Path path = OUT_DIR.resolve("with-annotations.pdf");
        try (PDDocument doc = new PDDocument()) {
            // Set document info
            PDDocumentInformation info = doc.getDocumentInformation();
            info.setTitle("Annotated Test Document");
            info.setAuthor("TrulyFreeOCR Test Suite");
            info.setSubject("Test PDF with annotations and bookmarks");
            info.setKeywords("test, annotations, bookmarks, pdf");

            List<PDPage> pages = new ArrayList<>();
            for (BufferedImage img : imgs) {
                PDImageXObject pdImg = JPEGFactory.createFromImage(doc, img, 0.95f);
                PDPage pdPage = new PDPage(new PDRectangle(W, H));
                doc.addPage(pdPage);
                pages.add(pdPage);
                try (PDPageContentStream cs = new PDPageContentStream(doc, pdPage)) {
                    cs.drawImage(pdImg, 0, 0, W, H);
                }
            }

            // Add text annotation (sticky note) on page 1
            PDAnnotationText textAnnot = new PDAnnotationText();
            textAnnot.setRectangle(new PDRectangle(100, H - 100, 200, 50));
            textAnnot.setContents("This is a sticky note annotation.");
            textAnnot.setOpen(false);
            pages.get(0).getAnnotations().add(textAnnot);

            // Add underline annotation on page 1
            PDAnnotationUnderline underline = new PDAnnotationUnderline();
            float[] quads = {
                MARGIN, H - MARGIN,
                MARGIN + 200, H - MARGIN,
                MARGIN, H - MARGIN - 20,
                MARGIN + 200, H - MARGIN - 20
            };
            underline.setRectangle(new PDRectangle(MARGIN, H - MARGIN - 20, 200, 20));
            underline.setQuadPoints(quads);
            underline.setContents("Underlined text");
            pages.get(0).getAnnotations().add(underline);

            // Add bookmarks (outline)
            PDDocumentOutline outline = new PDDocumentOutline();
            doc.getDocumentCatalog().setDocumentOutline(outline);

            for (int i = 0; i < titles.length; i++) {
                PDOutlineItem item = new PDOutlineItem();
                item.setTitle(titles[i]);
                PDPageFitWidthDestination dest = new PDPageFitWidthDestination();
                dest.setPage(pages.get(i));
                item.setDestination(dest);
                outline.addLast(item);
            }

            doc.save(path.toFile());
        }
        System.out.println("  with-annotations.pdf");
    }

    // ── 6. noisy-scan.pdf ───────────────────────────────────────────────

    private void makeNoisyScan() throws IOException {
        BufferedImage img = newPage();
        Graphics2D g = createGraphics(img);
        g.setFont(font14);

        String[] lines = {
            "This page simulates a noisy scanned document.",
            "It includes speckles, uneven lighting, and",
            "slight rotation to test image preprocessing.",
        };
        int y = MARGIN;
        for (String line : lines) {
            g.drawString(line, MARGIN, y);
            y += LINE_HEIGHT;
        }
        g.dispose();

        // Add noise and gradient directly on the raster
        Random rnd = new Random(42);
        int[] pixels = new int[W * H];
        img.getRGB(0, 0, W, H, pixels, 0, W);

        // Add speckle noise
        for (int i = 0; i < 3000; i++) {
            int x = rnd.nextInt(W);
            int y2 = rnd.nextInt(H);
            int v = rnd.nextInt(61);
            pixels[y2 * W + x] = (0xFF << 24) | (v << 16) | (v << 8) | v;
        }

        // Add gradient (darkening toward bottom)
        for (int y2 = 0; y2 < H; y2++) {
            int shade = (int) (30 * ((double) y2 / H));
            for (int x = 0; x < W; x++) {
                int rgb = pixels[y2 * W + x];
                int r = Math.max(0, ((rgb >> 16) & 0xFF) - shade);
                int g2 = Math.max(0, ((rgb >> 8) & 0xFF) - shade);
                int b = Math.max(0, (rgb & 0xFF) - shade);
                pixels[y2 * W + x] = (0xFF << 24) | (r << 16) | (g2 << 8) | b;
            }
        }

        img.setRGB(0, 0, W, H, pixels, 0, W);
        saveImagePdf(List.of(img), "noisy-scan.pdf");
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new TestPdfGenerator()).execute(args);
        System.exit(exitCode);
    }
}
