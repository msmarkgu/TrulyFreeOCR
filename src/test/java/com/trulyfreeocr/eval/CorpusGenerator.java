package com.trulyfreeocr.eval;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "generate-eval-corpus",
         version = "1.0.0",
         description = "Download text from Gutenberg and render image-only PDF corpus",
         mixinStandardHelpOptions = true)
public class CorpusGenerator implements Callable<Integer> {

    private static final int W = 612, H = 792; // US Letter at 72 DPI
    private static final int MARGIN = 50;
    private static final int FONT_SZ = 11;
    private static final int LINE_HEIGHT = 17;
    private static final int MAX_WIDTH = W - 2 * MARGIN;
    private static final int MAX_LINES = (H - MARGIN) / LINE_HEIGHT;

    private static final String GUTENBERG_URL =
            "https://www.gutenberg.org/files/1661/1661-0.txt";
    private static final String TITLE =
            "The Adventures of Sherlock Holmes";
    private static final String OUTPUT_STEM = "sherlock-holmes";
    private static final Path OUT_DIR = Path.of("tests", "eval-corpus");

    private static final int[] PAGE_COUNTS = {10, 50, 200, 500};

    private static final Pattern HEADER_PATTERN = Pattern.compile(
            "\\*\\*\\* START OF (THIS PROJECT|THE PROJECT) GUTENBERG EBOOK.*?\\*\\*\\*");
    private static final Pattern FOOTER_PATTERN = Pattern.compile(
            "\\*\\*\\* END OF (THIS PROJECT|THE PROJECT) GUTENBERG EBOOK.*?\\*\\*\\*");
    private static final Pattern DASH_LINE = Pattern.compile("[_=]{4,}");
    private static final Pattern ILLUSTRATION = Pattern.compile("^\\[Illustration");

    @Option(names = "--force", description = "Regenerate existing files")
    private boolean force;

    private Font font;

    @Override
    public Integer call() {
        try {
            Files.createDirectories(OUT_DIR);
            font = loadFont();

            System.out.println("  Downloading " + GUTENBERG_URL + "...");
            String text = downloadText(GUTENBERG_URL);
            text = stripGutenberg(text);
            System.out.println("  Corpus length: " + text.length() + " chars");

            List<String> lines = cleanLines(text);
            int totalPages = lines.size() / MAX_LINES + 1;
            System.out.println("  Text yields ~" + totalPages + " pages at "
                    + MAX_LINES + " lines/page");

            for (int pc : PAGE_COUNTS) {
                String stem = OUTPUT_STEM + "-" + String.format("%03d", pc) + "p";
                Path pdfPath = OUT_DIR.resolve(stem + ".pdf");
                if (Files.exists(pdfPath) && !force) {
                    System.out.println("  " + stem + " exists, skipping (use --force to regenerate)");
                    continue;
                }
                if (pc > totalPages) {
                    System.out.println("  Warning: requested " + pc
                            + " pages but corpus has only ~" + totalPages + "; truncating");
                }
                generate(lines, Math.min(pc, totalPages), stem);
            }

            System.out.println("Done.");
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    // ── Text download & cleanup ──────────────────────────────────────────

    private String downloadText(String urlStr) throws IOException {
        URL url = new URL(urlStr);
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(url.openStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    private String stripGutenberg(String text) {
        var m = HEADER_PATTERN.matcher(text);
        if (m.find()) {
            text = text.substring(m.end());
        }
        m = FOOTER_PATTERN.matcher(text);
        if (m.find()) {
            text = text.substring(0, m.start());
        }
        return text.strip();
    }

    private List<String> cleanLines(String text) {
        List<String> result = new ArrayList<>();
        for (String line : text.split("\n")) {
            line = line.strip();
            if (line.isEmpty()) continue;
            if (DASH_LINE.matcher(line).find()) continue;
            if (ILLUSTRATION.matcher(line).find()) continue;
            result.add(line);
        }
        return result;
    }

    // ── Font loading ─────────────────────────────────────────────────────

    private Font loadFont() {
        // Try DejaVu Sans from standard system path, fall back to SansSerif
        File ttf = new File("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf");
        if (ttf.exists()) {
            try {
                return Font.createFont(Font.TRUETYPE_FONT, ttf).deriveFont((float) FONT_SZ);
            } catch (Exception e) {
                // fall through
            }
        }
        return new Font("SansSerif", Font.PLAIN, FONT_SZ);
    }

    // ── PDF + JSON generation ────────────────────────────────────────────

    private void generate(List<String> lines, int pageCount, String stem)
            throws IOException {

        // Partition lines into pages, collecting ground-truth words per page
        List<List<String>> pageWords = new ArrayList<>();
        List<List<String>> pageLines = new ArrayList<>();

        List<String> currentLines = new ArrayList<>();
        List<String> currentWords = new ArrayList<>();

        for (String line : lines) {
            currentLines.add(line);
            if (!line.isEmpty()) {
                for (String w : line.split("\\s+")) {
                    if (!w.isEmpty()) currentWords.add(w);
                }
            }
            if (currentLines.size() >= MAX_LINES) {
                pageWords.add(currentWords);
                pageLines.add(currentLines);
                currentLines = new ArrayList<>();
                currentWords = new ArrayList<>();
                if (pageWords.size() >= pageCount) break;
            }
        }

        if (!currentLines.isEmpty() && pageWords.size() < pageCount) {
            pageWords.add(currentWords);
            pageLines.add(currentLines);
        }

        // Truncate
        while (pageWords.size() > pageCount) {
            pageWords.remove(pageWords.size() - 1);
            pageLines.remove(pageLines.size() - 1);
        }

        // Build PDF
        Path pdfPath = OUT_DIR.resolve(stem + ".pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pageLines.size(); i++) {
                BufferedImage img = renderPage(pageLines.get(i));
                PDImageXObject pdImage = JPEGFactory.createFromImage(doc, img, 0.95f);
                PDPage pdPage = new PDPage(new PDRectangle(W, H));
                doc.addPage(pdPage);
                try (PDPageContentStream cs = new PDPageContentStream(doc, pdPage)) {
                    cs.drawImage(pdImage, 0, 0, W, H);
                }
            }
            doc.save(pdfPath.toFile());
        }
        int wordCount = pageWords.stream().mapToInt(List::size).sum();
        System.out.println("    " + pdfPath + " (" + pageWords.size()
                + " pages, " + wordCount + " words)");

        // Write JSON
        writeJson(stem, pageWords);
    }

    private BufferedImage renderPage(List<String> lines) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.setColor(Color.BLACK);
        g.setFont(font);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int y = MARGIN;
        for (String line : lines) {
            if (!line.isEmpty()) {
                g.drawString(line, MARGIN, y);
            }
            y += LINE_HEIGHT;
        }
        g.dispose();
        return img;
    }

    // ── JSON writer ──────────────────────────────────────────────────────

    private void writeJson(String stem, List<List<String>> pageWords)
            throws IOException {
        Path jsonPath = OUT_DIR.resolve(stem + ".json");
        try (Writer w = new OutputStreamWriter(
                Files.newOutputStream(jsonPath), StandardCharsets.UTF_8)) {
            w.write("{\n");
            w.write("  \"source\": \"" + escapeJson(GUTENBERG_URL) + "\",\n");
            w.write("  \"title\": \"" + escapeJson(TITLE) + "\",\n");
            w.write("  \"pages\": [\n");
            for (int i = 0; i < pageWords.size(); i++) {
                w.write("    {\"page\": " + (i + 1) + ", \"words\": [");
                List<String> words = pageWords.get(i);
                for (int j = 0; j < words.size(); j++) {
                    if (j > 0) w.write(", ");
                    w.write("\"" + escapeJson(words.get(j)) + "\"");
                }
                w.write("]}");
                if (i < pageWords.size() - 1) w.write(",");
                w.write("\n");
            }
            w.write("  ]\n");
            w.write("}\n");
        }
        System.out.println("    " + jsonPath);
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // ── main ─────────────────────────────────────────────────────────────

    public static void main(String[] args) {
        int exitCode = new CommandLine(new CorpusGenerator()).execute(args);
        System.exit(exitCode);
    }
}
