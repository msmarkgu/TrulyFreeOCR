package com.trulyfreeocr.pipeline;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import com.trulyfreeocr.model.PageResult;
import com.trulyfreeocr.model.TextBlock;

public class PaddleOcrOnnxProvider implements OcrProvider {

    private static final String MODEL_DIR = "deps/paddleocr";
    private static final String DET_MODEL = "det.onnx";
    private static final String REC_MODEL = "rec.onnx";

    // Detection preprocessing
    private static final int DET_LIMIT_SIDE = 960;
    private static final String DET_LIMIT_TYPE = "max";

    // DB post-processing
    private static final float DET_THRESH = 0.3f;
    private static final float BOX_THRESH = 0.6f;
    private static final float UNCLIP_RATIO = 1.5f;
    private static final int MIN_SIZE = 3;

    // Recognition
    private static final int REC_HEIGHT = 48;
    private static final int REC_MAX_WIDTH = 3200;

    private final OrtEnvironment env;
    private final OrtSession detSession;
    private final String detInputName;
    private final String detOutputName;

    private final OrtSession recSession;
    private final String recInputName;
    private final String recOutputName;

    private final List<String> charDict;

    public PaddleOcrOnnxProvider() throws IOException {
        try {
            env = OrtEnvironment.getEnvironment();

            Path detPath = Path.of(MODEL_DIR, DET_MODEL);
            if (!detPath.toFile().exists()) {
                throw new IOException("Detection model not found: " + detPath.toAbsolutePath()
                        + ". Run bootstrap.sh to download PP-OCRv6 models.");
            }

            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            detSession = env.createSession(detPath.toString(), opts);
            detInputName = detSession.getInputNames().iterator().next();
            detOutputName = detSession.getOutputNames().iterator().next();

            Path recPath = Path.of(MODEL_DIR, REC_MODEL);
            if (recPath.toFile().exists()) {
                recSession = env.createSession(recPath.toString(), opts);
                recInputName = recSession.getInputNames().iterator().next();
                recOutputName = recSession.getOutputNames().iterator().next();
            } else {
                recSession = null;
                recInputName = null;
                recOutputName = null;
            }

            charDict = loadCharDict();

        } catch (OrtException e) {
            throw new IOException("Failed to initialize ONNX Runtime", e);
        }
    }

    private static List<String> loadCharDict() throws IOException {
        Path dictPath = Path.of(MODEL_DIR, "ppocr_keys_v6.txt");
        if (!dictPath.toFile().exists()) {
            dictPath = Path.of(MODEL_DIR, "ppocr_keys_v1.txt");
        }
        if (!dictPath.toFile().exists()) {
            return null;
        }
        List<String> rawChars = Files.readAllLines(dictPath);
        // Build full dict matching model output:
        //   [0]       = blank token (CTC blank, ignored during decode)
        //   [1..N]    = actual characters from the dict file
        //   [N+1]     = space character
        // Total size = N + 2, matching model's vocab_size
        List<String> dict = new ArrayList<>(rawChars.size() + 2);
        dict.add("");  // index 0 = blank
        dict.addAll(rawChars);
        dict.add(" "); // index N+1 = space
        return dict;
    }

    // ── OcrProvider ──────────────────────────────────────────────────────────

    @Override
    public PageResult ocr(BufferedImage pageImage, int pageIndex) throws IOException {
        int width = pageImage.getWidth();
        int height = pageImage.getHeight();

        List<DetBox> boxes = detect(pageImage);
        if (boxes.isEmpty()) {
            return new PageResult(pageIndex + 1, width, height, List.of());
        }

        List<DetBox> sorted = sortBoxesReadingOrder(boxes);

        List<String> texts;
        if (recSession != null && charDict != null) {
            texts = recognize(pageImage, sorted);
        } else {
            texts = new ArrayList<>(sorted.size());
            for (int i = 0; i < sorted.size(); i++) texts.add("");
        }

        List<TextBlock> blocks = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            DetBox box = sorted.get(i);
            Rectangle r = new Rectangle(box.x, box.y, box.w, box.h);
            blocks.add(new TextBlock(texts.get(i), r, box.confidence));
        }

        return new PageResult(pageIndex + 1, width, height, blocks);
    }

    // ── Detection Pipeline ───────────────────────────────────────────────────

    private static class DetBox {
        int x, y, w, h;
        float confidence;
        DetBox(int x, int y, int w, int h, float confidence) {
            this.x = x; this.y = y; this.w = w; this.h = h; this.confidence = confidence;
        }
    }

    private List<DetBox> detect(BufferedImage image) throws IOException {
        int origW = image.getWidth();
        int origH = image.getHeight();

        ResizeResult resized = detResize(image, DET_LIMIT_SIDE, DET_LIMIT_TYPE);
        float[][][][] inputTensor = detPreprocess(resized.image);

        float[][][][] probMap;
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, inputTensor);
             OrtSession.Result result = detSession.run(Map.of(detInputName, tensor))) {

            probMap = (float[][][][]) result.get(detOutputName).get().getValue();
        } catch (OrtException e) {
            throw new IOException("ONNX detection inference failed", e);
        }

        int mapH = probMap[0][0].length;
        int mapW = probMap[0][0][0].length;

        float scaleX = (float) origW / resized.newW;
        float scaleY = (float) origH / resized.newH;

        return dbPostprocess(probMap[0][0], mapW, mapH, scaleX, scaleY);
    }

    // ── Detection Preprocessing ──────────────────────────────────────────────

    private static class ResizeResult {
        BufferedImage image;
        int newW, newH;
        ResizeResult(BufferedImage image, int newW, int newH) {
            this.image = image; this.newW = newW; this.newH = newH;
        }
    }

    private static ResizeResult detResize(BufferedImage image, int limitSide, String limitType) {
        int h = image.getHeight();
        int w = image.getWidth();

        float ratio;
        if ("max".equals(limitType)) {
            int maxSide = Math.max(h, w);
            ratio = maxSide > limitSide ? (float) limitSide / maxSide : 1f;
        } else {
            int minSide = Math.min(h, w);
            ratio = (float) limitSide / minSide;
        }

        int newH = (int) (h * ratio);
        int newW = (int) (w * ratio);

        newH = Math.max(Math.round(newH / 32f) * 32, 32);
        newW = Math.max(Math.round(newW / 32f) * 32, 32);

        BufferedImage resized = new BufferedImage(newW, newH, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = resized.createGraphics();
        g.drawImage(image, 0, 0, newW, newH, null);
        g.dispose();

        return new ResizeResult(resized, newW, newH);
    }

    private static float[][][][] detPreprocess(BufferedImage image) {
        int h = image.getHeight();
        int w = image.getWidth();
        float[][][][] input = new float[1][3][h][w];

        // ImageNet normalization: scale=1/255, mean=[0.485,0.456,0.406], std=[0.229,0.224,0.225]
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = image.getRGB(x, y);
                float b = ((rgb >> 0) & 0xFF) / 255f;
                float g = ((rgb >> 8) & 0xFF) / 255f;
                float r = ((rgb >> 16) & 0xFF) / 255f;

                input[0][0][y][x] = (b - 0.485f) / 0.229f;
                input[0][1][y][x] = (g - 0.456f) / 0.224f;
                input[0][2][y][x] = (r - 0.406f) / 0.225f;
            }
        }
        return input;
    }

    // ── DB Postprocessing ────────────────────────────────────────────────────

    private static List<DetBox> dbPostprocess(float[][] probMap, int mapW, int mapH,
                                               float scaleX, float scaleY) {
        int h = probMap.length;
        int w = probMap[0].length;

        boolean[][] binary = new boolean[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                binary[y][x] = probMap[y][x] > DET_THRESH;
            }
        }

        int[][] labels = connectedComponents(binary);

        int maxLabel = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (labels[y][x] > maxLabel) maxLabel = labels[y][x];
            }
        }

        List<DetBox> boxes = new ArrayList<>();
        for (int label = 1; label <= maxLabel; label++) {
            DetBox box = computeBoxForLabel(probMap, labels, label, h, w, mapW, mapH, scaleX, scaleY);
            if (box != null) {
                boxes.add(box);
            }
        }

        return boxes;
    }

    private static int[][] connectedComponents(boolean[][] binary) {
        int h = binary.length;
        int w = binary[0].length;
        int[][] labels = new int[h][w];
        int[] eq = new int[h * w / 2 + 1];
        int nextLabel = 0;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!binary[y][x]) continue;

                int left = (x > 0 && binary[y][x - 1]) ? labels[y][x - 1] : 0;
                int top = (y > 0 && binary[y - 1][x]) ? labels[y - 1][x] : 0;
                int topLeft = (x > 0 && y > 0 && binary[y - 1][x - 1]) ? labels[y - 1][x - 1] : 0;
                int topRight = (x + 1 < w && y > 0 && binary[y - 1][x + 1]) ? labels[y - 1][x + 1] : 0;

                int minLabel = Integer.MAX_VALUE;
                boolean hasNeighbor = false;
                for (int v : new int[]{left, top, topLeft, topRight}) {
                    if (v > 0) {
                        minLabel = Math.min(minLabel, v);
                        hasNeighbor = true;
                    }
                }

                if (!hasNeighbor) {
                    nextLabel++;
                    labels[y][x] = nextLabel;
                    if (nextLabel >= eq.length) break;
                } else {
                    labels[y][x] = minLabel;
                    for (int v : new int[]{left, top, topLeft, topRight}) {
                        if (v > 0 && v != minLabel) {
                            union(eq, minLabel, v);
                        }
                    }
                }
            }
        }

        for (int i = 1; i <= nextLabel; i++) {
            eq[i] = find(eq, i);
        }

        int[] remap = new int[nextLabel + 1];
        int uniqueLabels = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (labels[y][x] > 0) {
                    int root = eq[labels[y][x]];
                    if (remap[root] == 0) {
                        uniqueLabels++;
                        remap[root] = uniqueLabels;
                    }
                    labels[y][x] = remap[root];
                }
            }
        }

        return labels;
    }

    private static void union(int[] eq, int a, int b) {
        int ra = find(eq, a);
        int rb = find(eq, b);
        if (ra != rb) eq[ra] = rb;
    }

    private static int find(int[] eq, int x) {
        while (eq[x] != 0 && eq[x] != x) {
            eq[x] = eq[eq[x]];
            x = eq[x];
        }
        return x;
    }

    private static DetBox computeBoxForLabel(float[][] probMap, int[][] labels, int targetLabel,
                                              int h, int w, int mapW, int mapH,
                                              float scaleX, float scaleY) {
        int minX = w, minY = h, maxX = 0, maxY = 0;
        float sumProb = 0;
        int count = 0;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (labels[y][x] == targetLabel) {
                    if (x < minX) minX = x;
                    if (y < minY) minY = y;
                    if (x > maxX) maxX = x;
                    if (y > maxY) maxY = y;
                    sumProb += probMap[y][x];
                    count++;
                }
            }
        }

        if (count == 0) return null;

        float confidence = sumProb / count;
        if (confidence < BOX_THRESH) return null;

        int boxW = maxX - minX + 1;
        int boxH = maxY - minY + 1;
        if (boxW < MIN_SIZE || boxH < MIN_SIZE) return null;

        float area = count;
        float perimeter = 2f * (boxW + boxH);
        if (perimeter < 1) return null;
        float distance = area * UNCLIP_RATIO / perimeter;

        int pad = Math.round(distance);
        minX = Math.max(0, minX - pad);
        minY = Math.max(0, minY - pad);
        maxX = Math.min(mapW - 1, maxX + pad);
        maxY = Math.min(mapH - 1, maxY + pad);

        float probScaleX = scaleX * (w / (float) mapW);
        float probScaleY = scaleY * (h / (float) mapH);

        int origX = Math.round(minX * probScaleX);
        int origY = Math.round(minY * probScaleY);
        int origW = Math.round((maxX - minX + 1) * probScaleX);
        int origH = Math.round((maxY - minY + 1) * probScaleY);

        return new DetBox(origX, origY, origW, origH, confidence);
    }

    // ── Reading Order Sort ───────────────────────────────────────────────────

    private static List<DetBox> sortBoxesReadingOrder(List<DetBox> boxes) {
        List<DetBox> sorted = new ArrayList<>(boxes);
        sorted.sort((a, b) -> {
            int midY_a = a.y + a.h / 2;
            int midY_b = b.y + b.h / 2;
            if (Math.abs(midY_a - midY_b) > Math.min(a.h, b.h) / 2) {
                return Integer.compare(midY_a, midY_b);
            }
            return Integer.compare(a.x, b.x);
        });
        return sorted;
    }

    // ── Recognition Pipeline ─────────────────────────────────────────────────

    private List<String> recognize(BufferedImage image, List<DetBox> boxes) throws IOException {
        List<String> texts = new ArrayList<>(boxes.size());
        for (DetBox box : boxes) {
            BufferedImage crop = cropBox(image, box);
            if (crop == null) {
                texts.add("");
                continue;
            }

            float[][][][] inputTensor = recPreprocess(crop);

            float[][] logits;
            try (OnnxTensor tensor = OnnxTensor.createTensor(env, inputTensor);
                 OrtSession.Result result = recSession.run(Map.of(recInputName, tensor))) {

                float[][][] raw = (float[][][]) result.get(recOutputName).get().getValue();
                logits = raw[0]; // [T, vocab_size]
            } catch (OrtException e) {
                throw new IOException("ONNX recognition inference failed", e);
            }

            String text = ctcDecode(logits, charDict);
            texts.add(text);
        }
        return texts;
    }

    private static BufferedImage cropBox(BufferedImage image, DetBox box) {
        int x = Math.max(0, Math.min(box.x, image.getWidth() - 1));
        int y = Math.max(0, Math.min(box.y, image.getHeight() - 1));
        int w = Math.min(box.w, image.getWidth() - x);
        int h = Math.min(box.h, image.getHeight() - y);
        if (w < 1 || h < 1) return null;
        return image.getSubimage(x, y, w, h);
    }

    private static float[][][][] recPreprocess(BufferedImage crop) {
        int cw = crop.getWidth();
        int ch = crop.getHeight();

        // Dynamic width: preserve aspect ratio, height = 48
        int targetW = (int) Math.ceil(48f * cw / ch);
        targetW = Math.min(targetW, REC_MAX_WIDTH);
        targetW = Math.max(targetW, 4); // minimum width

        BufferedImage resized = new BufferedImage(targetW, REC_HEIGHT, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = resized.createGraphics();
        g.drawImage(crop, 0, 0, targetW, REC_HEIGHT, null);
        g.dispose();

        // Normalize to [-1, 1]: (x/255 - 0.5) / 0.5
        float[][][][] input = new float[1][3][REC_HEIGHT][targetW];
        for (int y = 0; y < REC_HEIGHT; y++) {
            for (int x = 0; x < targetW; x++) {
                int rgb = resized.getRGB(x, y);
                float b = ((rgb >> 0) & 0xFF) / 255f;
                float g2 = ((rgb >> 8) & 0xFF) / 255f;
                float r = ((rgb >> 16) & 0xFF) / 255f;

                input[0][0][y][x] = (b - 0.5f) / 0.5f;
                input[0][1][y][x] = (g2 - 0.5f) / 0.5f;
                input[0][2][y][x] = (r - 0.5f) / 0.5f;
            }
        }
        return input;
    }

    private static String ctcDecode(float[][] logits, List<String> charDict) {
        int T = logits.length;
        int vocab = logits[0].length;

        StringBuilder sb = new StringBuilder();
        int prevIdx = -1;

        for (int t = 0; t < T; t++) {
            int maxIdx = 0;
            float maxVal = logits[t][0];
            for (int c = 1; c < vocab; c++) {
                if (logits[t][c] > maxVal) {
                    maxVal = logits[t][c];
                    maxIdx = c;
                }
            }

            if (maxIdx == 0) {
                prevIdx = -1;
                continue;
            }

            if (maxIdx != prevIdx) {
                if (maxIdx < charDict.size()) {
                    sb.append(charDict.get(maxIdx));
                }
                prevIdx = maxIdx;
            }
        }

        return sb.toString().trim();
    }
}
