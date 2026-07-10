package com.trulyfreeocr.pipeline;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import com.trulyfreeocr.model.PageResult;
import com.trulyfreeocr.model.TextBlock;
import com.trulyfreeocr.util.PlatformUtils;

public class OCREngine {

    private final File tessdataDir;
    private final String tesseractPath;
    private final String language;
    private final String psm;

    public OCREngine() {
        this("deps/tesseract/tessdata", "deps/tesseract/linux/tesseract", "eng", "1");
    }

    public OCREngine(String tessdataDir, String tesseractPath, String language, String psm) {
        this.tesseractPath = tesseractPath;
        this.language = language;
        this.psm = psm;

        if (tessdataDir != null && !tessdataDir.isEmpty()) {
            this.tessdataDir = new File(tessdataDir);
        } else {
            String env = System.getenv("TESSDATA_PREFIX");
            if (env != null && !env.isEmpty()) {
                this.tessdataDir = new File(env);
            } else {
                this.tessdataDir = new File("/usr/share/tesseract-ocr/5/tessdata");
            }
        }
    }

    public PageResult ocr(int pageIndex, File tempDir) throws IOException {
        File imageFile = new File(tempDir, "page-" + pageIndex + ".bmp");
        BufferedImage tmp = ImageIO.read(imageFile);
        int width = tmp.getWidth();
        int height = tmp.getHeight();
        tmp = null;

        String prefix = imageFile.getAbsolutePath().replaceAll("\\.bmp$", "");
        Process process = null;

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    tesseractPath,
                    imageFile.getAbsolutePath(),
                    prefix,
                    "-l", language,
                    "--psm", psm,
                    "tsv"
            );
            pb.environment().put("TESSDATA_PREFIX", tessdataDir.getAbsolutePath());
            // Prevent Tesseract from spawning its own internal thread pool (OpenMP/LSTM).
            // Without this, each concurrent Tesseract subprocess greedily claims all CPU
            // cores, causing N parallel invocations to thrash each other and time out.
            pb.environment().put("OMP_THREAD_LIMIT", "1");
            pb.redirectErrorStream(true);
            File nullDevice = PlatformUtils.detectOs() == PlatformUtils.Os.WINDOWS
                    ? new File("NUL")
                    : new File("/dev/null");
            pb.redirectInput(ProcessBuilder.Redirect.from(nullDevice));

            process = pb.start();
            Process proc = process;
            Thread drainer = new Thread(() -> {
                try (InputStream is = proc.getInputStream()) {
                    byte[] buf = new byte[4096];
                    while (is.read(buf) != -1) { }
                } catch (IOException ignored) { }
            }, "tesseract-stdout-drain");
            drainer.setDaemon(true);
            drainer.start();
            boolean exited = process.waitFor(300, java.util.concurrent.TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                throw new IOException("Tesseract timed out after 300 seconds");
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new IOException("Tesseract exited with code " + exitCode);
            }

            File tsvFile = new File(prefix + ".tsv");
            if (!tsvFile.exists()) {
                return new PageResult(pageIndex + 1, width, height, List.of());
            }

            List<TextBlock> blocks = parseTsv(tsvFile);
            return new PageResult(pageIndex + 1, width, height, blocks);

        } catch (InterruptedException e) {
            if (process != null) process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Tesseract was interrupted", e);
        } finally {
            for (String ext : new String[]{".tsv"}) {
                File f = new File(prefix + ext);
                if (f.exists()) f.delete();
            }
        }
    }

    private List<TextBlock> parseTsv(File tsv) throws IOException {
        List<TextBlock> blocks = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(tsv), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null) return blocks;

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] parts = line.split("\t");
                if (parts.length < 12) continue;

                String level = parts[0];
                String text = parts[11].trim();
                if (!"5".equals(level) || text.isEmpty()) continue;

                try {
                    int left = Integer.parseInt(parts[6]);
                    int top = Integer.parseInt(parts[7]);
                    int w = Integer.parseInt(parts[8]);
                    int h = Integer.parseInt(parts[9]);
                    double conf = Double.parseDouble(parts[10]);

                    blocks.add(new TextBlock(text, new Rectangle(left, top, w, h), conf));
                } catch (NumberFormatException e) {
                }
            }
        }
        return blocks;
    }
}
