package com.trulyfreeocr.pipeline;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.image.CCITTFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import com.trulyfreeocr.util.PlatformUtils;
import com.trulyfreeocr.util.PlatformUtils.Os;
import com.trulyfreeocr.util.Settings;
import com.trulyfreeocr.util.SubprocessRunner;

/**
 * Compresses a binary foreground mask via jbig2enc subprocess,
 * falling back to CCITT Group 4 fax encoding via PDFBox when
 * the native binary is unavailable.
 *
 * jbig2enc flags:
 *   -p  — Produce a global symbol dictionary (shared across pages).
 *   -s  — Refine smearing to improve symbol matching.
 */
public class JBIG2Compressor {

    private final String nativeDir;

    public JBIG2Compressor() {
        this("deps/jbig2enc");
    }

    public JBIG2Compressor(String nativeDir) {
        this.nativeDir = nativeDir;
    }

    /**
     * Holds compressed foreground-mask data.
     *
     * isJbig2  true = JBIG2 format (from jbig2enc); false = CCITT G4 fallback.
     * data     The compressed byte stream.
     * width    Original mask width  in pixels.
     * height   Original mask height in pixels.
     */
    public static class CompressionResult {
        private final boolean isJbig2;
        private final byte[] data;
        private final int width;
        private final int height;

        public CompressionResult(byte[] data, int width, int height, boolean isJbig2) {
            this.data = data;
            this.width = width;
            this.height = height;
            this.isJbig2 = isJbig2;
        }

        public boolean isJbig2() { return isJbig2; }
        public byte[] getData() { return data; }
        public int getWidth() { return width; }
        public int getHeight() { return height; }
    }

    /**
     * Holds the output of a batch JBIG2 compression run:
     * a single shared global symbol dictionary and per-page page data.
     */
    public static class BatchResult {
        private final byte[] globalSym;
        private final List<CompressionResult> pages;

        public BatchResult(byte[] globalSym, List<CompressionResult> pages) {
            this.globalSym = globalSym;
            this.pages = pages;
        }

        public byte[] getGlobalSym() { return globalSym; }
        public List<CompressionResult> getPages() { return pages; }
    }

    /**
     * Compresses foreground mask PNGs in a single jbig2enc invocation
     * with the {@code -p} flag, producing one shared symbol dictionary
     * and per-page JBIG2 streams.
     *
     * @param maskDir    Directory containing mask PNGs named {@code mask-0.png},
     *                   {@code mask-1.png}, ..., {@code mask-{pageCount-1}.png}.
     * @param pageCount  Number of masks / pages.
     * @param imgWidth   Width of each mask in pixels.
     * @param imgHeight  Height of each mask in pixels.
     * @return BatchResult with shared global sym and per-page data, or null if
     *         jbig2enc is unavailable or fails (caller should use CCITT fallback).
     */
    public BatchResult compressAllFromDir(File maskDir, int pageCount, int[] imgWidths, int[] imgHeights) throws IOException {
        File jbig2enc = findJbig2enc();
        if (jbig2enc == null) return null;

        List<String> imagePaths = new ArrayList<>(pageCount);
        for (int i = 0; i < pageCount; i++) {
            imagePaths.add(new File(maskDir, "mask-" + i + ".bmp").getAbsolutePath());
        }

        // Use a UUID-scoped basename to keep JBIG2 output files (.sym, .0000, ...)
        // separate from input BMPs and avoid collision between concurrent runs.
        String basename = new File(maskDir, "jbig2-" + UUID.randomUUID()).getAbsolutePath();

        String flags = Settings.getInstance().getString("jbig2enc.flags", "-p -s");
        List<String> args = new ArrayList<>();
        String[] parts = flags.split("\\s+");
        for (int i = 0; i < parts.length; i++) {
            if ("-b".equals(parts[i])) { i++; continue; }
            if (!parts[i].isEmpty()) args.add(parts[i]);
        }
        args.add("-b");
        args.add(basename);
        args.addAll(imagePaths);

        SubprocessRunner.Result result = SubprocessRunner.run(jbig2enc, args, -1);
        if (result.getExitCode() != 0) return null;

        // Read output files, then delete them immediately
        try {
            // Read shared global symbol dictionary
            File symFile = new File(basename + ".sym");
            byte[] globalSym = symFile.exists() ? Files.readAllBytes(symFile.toPath()) : new byte[0];
            if (globalSym.length == 0) return null;

            // Read per-page data (jbig2enc -p writes <basename>.0000, .0001, ...)
            List<CompressionResult> pages = new ArrayList<>(pageCount);
            for (int i = 0; i < pageCount; i++) {
                File pageFile = new File(basename + "." + String.format("%04d", i));
                byte[] pageData = pageFile.exists() ? Files.readAllBytes(pageFile.toPath()) : new byte[0];
                pages.add(new CompressionResult(pageData, imgWidths[i], imgHeights[i], true));
            }

            return new BatchResult(globalSym, pages);
        } finally {
            new File(basename + ".sym").delete();
            for (int i = 0; i < pageCount; i++) {
                new File(basename + "." + String.format("%04d", i)).delete();
            }
        }
    }

    /**
     * Attempts JBIG2 compression via jbig2enc; falls back to CCITT G4 on failure.
     *
     * @param foregroundMask  Binary image (TYPE_BYTE_BINARY, black=foreground).
     * @return CompressionResult with either JBIG2 or CCITT G4 data.
     */
    public CompressionResult compress(BufferedImage foregroundMask) throws IOException {
        File jbig2enc = findJbig2enc();
        if (jbig2enc != null) {
            try {
                return compressWithJbig2enc(jbig2enc, foregroundMask);
            } catch (IOException e) {
                // jbig2enc failed → fall through to fallback
            }
        }
        return compressWithFallback(foregroundMask);
    }

    /**
     * Locates the jbig2enc binary for the current OS.
     *
     * Searches deps/jbig2enc/{os}/jbig2enc (or jbig2enc.exe on Windows).
     * The base path can be overridden via system property "tfocr.native.dir"
     * (defaults to "native").
     *
     * @return The executable File, or null if not found.
     */
    private File findJbig2enc() {
        Os os = PlatformUtils.detectOs();
        String dirName;
        switch (os) {
            case WINDOWS: dirName = "win"; break;
            case MAC: dirName = "mac"; break;
            default: dirName = "linux";
        }
        File exe = new File(nativeDir + "/" + dirName, os == Os.WINDOWS ? "jbig2enc.exe" : "jbig2enc");
        if (exe.canExecute()) return exe;
        return null;
    }

    /**
     * Runs jbig2enc and concatenates global sym + page jbig2 into one byte array.
     *
     * PDF JBIG2Decode streams require global segments (symbol dictionary)
     * followed by page data — exactly the .sym + .jbig2 layout that
     * jbig2enc produces.
     */
    private CompressionResult compressWithJbig2enc(File jbig2enc, BufferedImage mask) throws IOException {
        Files.createDirectories(Path.of("temp"));
        File tempBmp = Files.createTempFile(Path.of("temp"), "tfocr-jbig2-", ".bmp").toFile();
        tempBmp.deleteOnExit();
        javax.imageio.ImageIO.write(mask, "bmp", tempBmp);

        try {
            String prefix = tempBmp.getAbsolutePath().replaceAll("\\.bmp$", "");

            String flags = Settings.getInstance().getString("jbig2enc.flags", "-p -s");
            java.util.List<String> args = new java.util.ArrayList<>();
            String[] parts = flags.split("\\s+");
            for (int i = 0; i < parts.length; i++) {
                if ("-b".equals(parts[i])) { i++; continue; }
                if (!parts[i].isEmpty()) args.add(parts[i]);
            }
            args.add("-b");
            args.add(prefix);
            args.add(tempBmp.getAbsolutePath());

            SubprocessRunner.Result result = SubprocessRunner.run(jbig2enc, args, -1);
            if (result.getExitCode() != 0) {
                throw new IOException("jbig2enc failed: " + result.getStderrString());
            }

            // With -p flag, jbig2enc produces <prefix>.sym + <prefix>.0000
            File symFile = new File(prefix + ".sym");
            File pageFile = new File(prefix + ".0000");

            byte[] symData = symFile.exists() ? java.nio.file.Files.readAllBytes(symFile.toPath()) : new byte[0];
            byte[] pageData = pageFile.exists() ? java.nio.file.Files.readAllBytes(pageFile.toPath()) : new byte[0];

            byte[] combined = new byte[symData.length + pageData.length];
            System.arraycopy(symData, 0, combined, 0, symData.length);
            System.arraycopy(pageData, 0, combined, symData.length, pageData.length);

            return new CompressionResult(combined, mask.getWidth(), mask.getHeight(), true);
        } finally {
            String prefix = tempBmp.getAbsolutePath().replaceAll("\\.bmp$", "");
            for (String ext : new String[]{".bmp", ".sym", ".0000"}) {
                File f = new File(prefix + ext);
                if (f.exists()) f.delete();
            }
        }
    }

    /**
     * Fallback: encodes the mask as CCITT Group 4 fax using PDFBox's CCITTFactory.
     */
    private CompressionResult compressWithFallback(BufferedImage mask) throws IOException {
        // Create a throwaway PDDocument to use CCITTFactory
        try (PDDocument doc = new PDDocument()) {
            PDImageXObject ccitt = CCITTFactory.createFromImage(doc, mask);
            return new CompressionResult(extractStreamBytes(ccitt), mask.getWidth(), mask.getHeight(), false);
        }
    }

    private static byte[] extractStreamBytes(PDImageXObject image) throws IOException {
        try (java.io.InputStream is = image.createInputStream()) {
            return is.readAllBytes();
        }
    }
}
