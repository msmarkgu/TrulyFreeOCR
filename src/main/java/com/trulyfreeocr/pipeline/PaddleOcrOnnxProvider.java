package com.trulyfreeocr.pipeline;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import com.trulyfreeocr.model.PageResult;
import com.trulyfreeocr.model.TextBlock;

/**
 * OCR provider using PP-OCRv6 ONNX models (detection + recognition).
 * Supports multiple model tiers: medium (best quality), small, tiny (fastest).
 * Detection: DBNet (Differentiable Binarization) with orientation-aware post-processing.
 * Recognition: CRNN + CTC decode with per-model character dictionary.
 * <p>
 * Model files are resolved from {@code deps/paddleocr/{tier}/} with fallback
 * through smaller tiers, then to the flat {@code deps/paddleocr/} directory
 * for backward compatibility.
 */
public class PaddleOcrOnnxProvider implements OcrProvider {

    private static final String MODEL_DIR = "deps/paddleocr";
    private static final String DET_MODEL = "det.onnx";
    private static final String REC_MODEL = "rec.onnx";
    private static final String LANG_MODEL_DIR = "deps/paddleocr/languages";
    private static final String LANG_DICT_DIR = "deps/paddleocr/dict";

    /** Available tiers in descending quality order. */
    private static final String[] TIERS = {"medium", "small", "tiny"};

    // Max side for detection input image; larger images are scaled down to this limit.
    private static final int DET_LIMIT_SIDE = 960;
    private static final String DET_LIMIT_TYPE = "max";

    // DB binarization threshold — pixels above this are considered text.
    private static final float DET_THRESH = 0.3f;
    // Average probability threshold for a connected component to be kept as a text box.
    private static final float BOX_THRESH = 0.6f;
    // Expansion ratio applied to text contours during box unclipping.
    private static final float UNCLIP_RATIO = 1.5f;
    // Minimum box dimension in the probability map (pixels in resized space).
    private static final int MIN_SIZE = 3;

    // Recognition input height (fixed). Width is dynamic to preserve aspect ratio.
    private static final int REC_HEIGHT = 48;
    // Maximum allowed recognition input width.
    private static final int REC_MAX_WIDTH = 3200;
    // Maximum crops per batched recognition call.
    private static final int REC_BATCH_SIZE = 6;
    // Minimum ratio (narrowest/widest) for crops grouped in the same batch.
    private static final float REC_BATCH_WIDTH_RATIO = 0.6f;

    private final OrtEnvironment env;
    private final OrtSession detSession;
    private final String detInputName;
    private final String detOutputName;

    private final OrtSession recSession;
    private final String recInputName;
    private final String recOutputName;

    private final List<String> charDict;
    private final String paddleTier;

    /**
     * Loads PP-OCRv6 ONNX models and character dictionary.
     * Detection model is required; recognition model and dict are optional
     * (fallback yields axis-aligned boxes without text labels).
     * Uses the multilingual character dict from bootstrap.
     */
    public PaddleOcrOnnxProvider() throws IOException {
        this("eng", "medium");
    }

    /**
     * Loads PP-OCRv6 ONNX models and character dictionary for the given language.
     * Uses the default medium model tier.
     */
    public PaddleOcrOnnxProvider(String language) throws IOException {
        this(language, "medium");
    }

    /**
     * Loads PP-OCRv6 ONNX models for the given language and model tier.
     * Resolves models from {@code deps/paddleocr/{tier}/} with fallback through
     * smaller tiers, then to the flat directory for backward compatibility.
     *
     * @param language language code for character dict selection
     * @param tier     model size tier: medium (default), small, or tiny
     */
    public PaddleOcrOnnxProvider(String language, String tier) throws IOException {
        try {
            env = OrtEnvironment.getEnvironment();
            this.paddleTier = tier;

            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();

            // Resolve detection model with tier fallback
            Path detPath = resolveModelPath(tier, DET_MODEL);
            if (!detPath.toFile().exists()) {
                throw new IOException("Detection model not found: " + detPath.toAbsolutePath()
                        + ". Run bootstrap.sh --paddle to download PP-OCRv6 models.");
            }
            detSession = env.createSession(detPath.toString(), opts);
            detInputName = detSession.getInputNames().iterator().next();
            detOutputName = detSession.getOutputNames().iterator().next();

            // Resolve recognition model: prefer language-specific model,
            // fall back to tier-based multilingual model.
            Path recPath = resolveLanguageModel(language);
            if (recPath == null) {
                recPath = resolveModelPath(tier, REC_MODEL);
            }
            if (recPath != null && recPath.toFile().exists()) {
                recSession = env.createSession(recPath.toString(), opts);
                recInputName = recSession.getInputNames().iterator().next();
                recOutputName = recSession.getOutputNames().iterator().next();
            } else {
                recSession = null;
                recInputName = null;
                recOutputName = null;
            }

            charDict = loadCharDict(language);

        } catch (OrtException e) {
            throw new IOException("Failed to initialize ONNX Runtime", e);
        }
    }

    /**
     * Resolves a language-specific recognition model path.
     * Looks for {@code deps/paddleocr/languages/{lang}/rec.onnx}.
     * Returns null if the language model does not exist.
     */
    private static Path resolveLanguageModel(String language) {
        Path langPath = Path.of(LANG_MODEL_DIR, language, REC_MODEL);
        if (langPath.toFile().exists()) {
            return langPath;
        }
        return null;
    }

    /**
     * Resolves a model file path with fallback chain:
     * {@code deps/paddleocr/{tier}/file} → next smaller tier → flat dir.
     */
    private static Path resolveModelPath(String preferredTier, String modelFile) {
        // First try the preferred tier subdirectory
        Path tierPath = Path.of(MODEL_DIR, preferredTier, modelFile);
        if (tierPath.toFile().exists()) {
            return tierPath;
        }
        // Fall back through smaller tiers
        boolean foundPreferred = false;
        for (String t : TIERS) {
            if (t.equals(preferredTier)) {
                foundPreferred = true;
                continue;
            }
            if (!foundPreferred) continue; // haven't reached preferred yet
            Path fallback = Path.of(MODEL_DIR, t, modelFile);
            if (fallback.toFile().exists()) {
                return fallback;
            }
        }
        // Finally try the flat directory (backward compat)
        return Path.of(MODEL_DIR, modelFile);
    }

    private static List<String> loadCharDict(String language) throws IOException {
        Path dictPath = Path.of(LANG_DICT_DIR, language + "_dict.txt");
        if (!dictPath.toFile().exists()) {
            dictPath = Path.of(MODEL_DIR, "ppocr_keys_v6.txt");
        }
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

    // ── OcrProvider Interface ─────────────────────────────────────────────────

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
            Rectangle r = polygonBbox(box);
            // Scale DBNet confidence (0-1) to 0-100 to match Tesseract convention
            blocks.add(new TextBlock(texts.get(i), r, box.confidence * 100.0));
        }

        return new PageResult(pageIndex + 1, width, height, blocks);
    }

    // ── Detection Pipeline ───────────────────────────────────────────────────

    /**
     * Axis-aligned bounding box with optional oriented polygon for perspective-aware cropping.
     * polyX/polyY are non-null only for elongated components (e.g., text lines).
     */
    private static class DetBox {
        int x, y, w, h;
        int[] polyX, polyY;
        float confidence;
        DetBox(int x, int y, int w, int h, float confidence,
               int[] polyX, int[] polyY) {
            this.x = x; this.y = y; this.w = w; this.h = h;
            this.confidence = confidence;
            this.polyX = polyX; this.polyY = polyY;
        }
    }

    /**
     * Runs DBNet detection: resize → normalize → ONNX inference → binarization
     * → connected components → oriented box computation.
     */
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

    // ── DB Post-processing ────────────────────────────────────────────────────

    /**
     * Converts DBNet probability map into DetBox list via thresholding,
     * connected-component labelling, and oriented box fitting.
     */
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

        // First pass: collect stats for axis-aligned box + PCA
        double sumX = 0, sumY = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (labels[y][x] == targetLabel) {
                    if (x < minX) minX = x;
                    if (y < minY) minY = y;
                    if (x > maxX) maxX = x;
                    if (y > maxY) maxY = y;
                    sumProb += probMap[y][x];
                    sumX += x;
                    sumY += y;
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

        // ── Axis-aligned unclip (always computed) ──
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

        // ── Oriented polygon (only for elongated components, e.g. text lines) ──
        // Width >> height indicates a text line where perspective crop helps.
        int[] polyX = null;
        int[] polyY = null;
        if (boxW > boxH * 2) {
            double cx = sumX / count;
            double cy = sumY / count;
            double xx = 0, yy = 0, xy = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (labels[y][x] == targetLabel) {
                        double dx = x - cx;
                        double dy = y - cy;
                        xx += dx * dx;
                        yy += dy * dy;
                        xy += dx * dy;
                    }
                }
            }
            xx /= count; yy /= count; xy /= count;

            double theta = 0.5 * Math.atan2(2 * xy, xx - yy);
            double cosA = Math.cos(theta);
            double sinA = Math.sin(theta);

            double minU = Double.MAX_VALUE, maxU = -Double.MAX_VALUE;
            double minV = Double.MAX_VALUE, maxV = -Double.MAX_VALUE;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (labels[y][x] == targetLabel) {
                        double u = (x - cx) * cosA + (y - cy) * sinA;
                        double v = -(x - cx) * sinA + (y - cy) * cosA;
                        if (u < minU) minU = u;
                        if (u > maxU) maxU = u;
                        if (v < minV) minV = v;
                        if (v > maxV) maxV = v;
                    }
                }
            }

            minU -= distance;
            maxU += distance;
            minV -= distance;
            maxV += distance;

            double[][] uv = {{minU, minV}, {maxU, minV}, {maxU, maxV}, {minU, maxV}};
            polyX = new int[4];
            polyY = new int[4];
            for (int i = 0; i < 4; i++) {
                double px = cx + uv[i][0] * cosA - uv[i][1] * sinA;
                double py = cy + uv[i][0] * sinA + uv[i][1] * cosA;
                polyX[i] = Math.round((float) (px * probScaleX));
                polyY[i] = Math.round((float) (py * probScaleY));
            }
        }

        return new DetBox(origX, origY, origW, origH, confidence, polyX, polyY);
    }

    // ── Reading-Order Sort ───────────────────────────────────────────────────

    private static List<DetBox> sortBoxesReadingOrder(List<DetBox> boxes) {
        List<DetBox> sorted = new ArrayList<>(boxes);
        sorted.sort((a, b) -> {
            double midY_a = boxCenterY(a);
            double midY_b = boxCenterY(b);
            double avgH = (bboxHeight(a) + bboxHeight(b)) / 2.0;
            if (Math.abs(midY_a - midY_b) > avgH / 2) {
                return Double.compare(midY_a, midY_b);
            }
            return Double.compare(boxCenterX(a), boxCenterX(b));
        });
        return sorted;
    }

    private static Rectangle polygonBbox(DetBox box) {
        int[] polyX = box.polyX;
        int[] polyY = box.polyY;
        if (polyX == null) {
            return new Rectangle(box.x, box.y, box.w, box.h);
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (int i = 0; i < 4; i++) {
            if (polyX[i] < minX) minX = polyX[i];
            if (polyY[i] < minY) minY = polyY[i];
            if (polyX[i] > maxX) maxX = polyX[i];
            if (polyY[i] > maxY) maxY = polyY[i];
        }
        return new Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    private static double boxCenterX(DetBox b) {
        if (b.polyX != null) {
            return (b.polyX[0] + b.polyX[1] + b.polyX[2] + b.polyX[3]) / 4.0;
        }
        return b.x + b.w / 2.0;
    }

    private static double boxCenterY(DetBox b) {
        if (b.polyY != null) {
            return (b.polyY[0] + b.polyY[1] + b.polyY[2] + b.polyY[3]) / 4.0;
        }
        return b.y + b.h / 2.0;
    }

    private static double bboxHeight(DetBox b) {
        if (b.polyY != null) {
            int minY = Math.min(Math.min(b.polyY[0], b.polyY[1]), Math.min(b.polyY[2], b.polyY[3]));
            int maxY = Math.max(Math.max(b.polyY[0], b.polyY[1]), Math.max(b.polyY[2], b.polyY[3]));
            return maxY - minY + 1;
        }
        return b.h;
    }

    // ── Recognition Pipeline ─────────────────────────────────────────────────

    /**
     * Runs CRNN recognition on each crop, batching similar-width crops together
     * to minimize padding artifacts. Returns text labels in box order.
     */
    private List<String> recognize(BufferedImage image, List<DetBox> boxes) throws IOException {
        int n = boxes.size();
        if (n == 0) return new ArrayList<>();

        // Preprocess all crops and record widths
        List<float[][][][]> preproc = new ArrayList<>(n);
        int[] widths = new int[n];
        for (int i = 0; i < n; i++) {
            BufferedImage crop = cropPerspective(image, boxes.get(i));
            if (crop == null) {
                preproc.add(null);
                widths[i] = 0;
            } else {
                float[][][][] tensor = recPreprocess(crop);
                preproc.add(tensor);
                widths[i] = tensor[0][0][0].length;
            }
        }

        // Sort indices by width (ascending, nulls last)
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, Comparator.comparingInt(i -> widths[i] > 0 ? widths[i] : Integer.MAX_VALUE));

        String[] results = new String[n];

        // Greedy batching: group crops by width similarity to minimize padding artifacts
        int start = 0;
        while (start < n) {
            int end = start;
            int batchMinW = Integer.MAX_VALUE;
            int batchMaxW = 0;
            while (end < n && end - start < REC_BATCH_SIZE) {
                int idx = order[end];
                if (preproc.get(idx) == null) {
                    end++;
                    continue;
                }
                int w = widths[idx];
                if (batchMinW == Integer.MAX_VALUE) {
                    batchMinW = w;
                    batchMaxW = w;
                } else {
                    int newMax = Math.max(batchMaxW, w);
                    if (batchMinW < newMax * REC_BATCH_WIDTH_RATIO) {
                        break; // new crop too wide relative to batch minimum
                    }
                    batchMaxW = newMax;
                }
                end++;
            }

            // Process this batch
            int batchSize = end - start;
            int validCount = 0;
            for (int j = start; j < end; j++) {
                if (preproc.get(order[j]) != null) validCount++;
            }

            if (validCount == 0) {
                for (int j = start; j < end; j++) results[order[j]] = "";
                start = end;
                continue;
            }

            // Create batch tensor, zero-initialized (which is gray in [-1,1] space)
            float[][][][] batch = new float[batchSize][3][REC_HEIGHT][batchMaxW];
            for (int j = start; j < end; j++) {
                int idx = order[j];
                float[][][][] src = preproc.get(idx);
                if (src == null) continue;
                int srcW = widths[idx];
                for (int c = 0; c < 3; c++) {
                    for (int y = 0; y < REC_HEIGHT; y++) {
                        System.arraycopy(src[0][c][y], 0, batch[j - start][c][y], 0, srcW);
                    }
                }
            }

            // Run batched inference
            float[][][] rawOutput;
            try (OnnxTensor tensor = OnnxTensor.createTensor(env, batch);
                 OrtSession.Result result = recSession.run(Map.of(recInputName, tensor))) {
                rawOutput = (float[][][]) result.get(recOutputName).get().getValue();
            } catch (OrtException e) {
                throw new IOException("ONNX recognition inference failed", e);
            }

            // Decode each result; filter out very short text (likely false positives)
            for (int j = start; j < end; j++) {
                int idx = order[j];
                if (preproc.get(idx) != null) {
                    String text = ctcDecode(rawOutput[j - start], charDict);
                    results[idx] = text.length() <= 2 ? "" : text;
                } else {
                    results[idx] = "";
                }
            }

            start = end;
        }

        return Arrays.asList(results);
    }

    /**
     * Crops and deskews an oriented text region by rotating the polygon
     * upright via affine transform with bilinear interpolation.
     * Falls back to axis-aligned crop for boxes without polygon data.
     */
    private static BufferedImage cropPerspective(BufferedImage image, DetBox box) {
        if (box.polyX == null) {
            return cropBoxAligned(image, box);
        }
        int[] px = box.polyX;
        int[] py = box.polyY;

        // Destination dimensions from polygon side lengths
        double topW = Math.sqrt(
            (px[1] - px[0]) * (px[1] - px[0]) + (py[1] - py[0]) * (py[1] - py[0]));
        double botW = Math.sqrt(
            (px[3] - px[2]) * (px[3] - px[2]) + (py[3] - py[2]) * (py[3] - py[2]));
        double leftH = Math.sqrt(
            (px[3] - px[0]) * (px[3] - px[0]) + (py[3] - py[0]) * (py[3] - py[0]));
        double rightH = Math.sqrt(
            (px[2] - px[1]) * (px[2] - px[1]) + (py[2] - py[1]) * (py[2] - py[1]));

        int dstW = (int) Math.round(Math.max(topW, botW));
        int dstH = (int) Math.round(Math.max(leftH, rightH));
        if (dstW < 1 || dstH < 1) return null;

        // Clamp destination size
        dstW = Math.min(dstW, image.getWidth() * 2);
        dstH = Math.min(dstH, image.getHeight() * 2);

        // Angle of the top edge
        double angle = -Math.atan2(py[1] - py[0], px[1] - px[0]);
        double cosA = Math.cos(angle);
        double sinA = Math.sin(angle);

        // Centroid of polygon
        double cx = (px[0] + px[1] + px[2] + px[3]) / 4.0;
        double cy = (py[0] + py[1] + py[2] + py[3]) / 4.0;

        BufferedImage result = new BufferedImage(dstW, dstH, BufferedImage.TYPE_3BYTE_BGR);
        int imgW = image.getWidth();
        int imgH = image.getHeight();

        for (int dy = 0; dy < dstH; dy++) {
            for (int dx = 0; dx < dstW; dx++) {
                // Affine transform: rotate dst coords back by -angle around centroid
                double srcX = cx + (dx - dstW / 2.0) * cosA - (dy - dstH / 2.0) * sinA;
                double srcY = cy + (dx - dstW / 2.0) * sinA + (dy - dstH / 2.0) * cosA;

                if (srcX < 0 || srcX >= imgW - 1 || srcY < 0 || srcY >= imgH - 1) {
                    result.setRGB(dx, dy, 0xFFFFFF);
                    continue;
                }

                int rgb = bilinearInterpolate(image, srcX, srcY);
                result.setRGB(dx, dy, rgb);
            }
        }
        return result;
    }

    private static BufferedImage cropBoxAligned(BufferedImage image, DetBox box) {
        int x = Math.max(0, Math.min(box.x, image.getWidth() - 1));
        int y = Math.max(0, Math.min(box.y, image.getHeight() - 1));
        int w = Math.min(box.w, image.getWidth() - x);
        int h = Math.min(box.h, image.getHeight() - y);
        if (w < 1 || h < 1) return null;
        return image.getSubimage(x, y, w, h);
    }

    /**
     * Bilinear interpolation at non-integer image coordinates.
     * Clamps to edge pixels when near the image boundary.
     */
    private static int bilinearInterpolate(BufferedImage img, double x, double y) {
        int ix = (int) x;
        int iy = (int) y;
        double fx = x - ix;
        double fy = y - iy;

        int c00 = img.getRGB(ix, iy);
        int c10 = img.getRGB(Math.min(ix + 1, img.getWidth() - 1), iy);
        int c01 = img.getRGB(ix, Math.min(iy + 1, img.getHeight() - 1));
        int c11 = img.getRGB(Math.min(ix + 1, img.getWidth() - 1),
                             Math.min(iy + 1, img.getHeight() - 1));

        int r = (int) Math.round(
            (1 - fy) * ((1 - fx) * ((c00 >> 16) & 0xFF) + fx * ((c10 >> 16) & 0xFF)) +
            fy * ((1 - fx) * ((c01 >> 16) & 0xFF) + fx * ((c11 >> 16) & 0xFF)));
        int g = (int) Math.round(
            (1 - fy) * ((1 - fx) * ((c00 >> 8) & 0xFF) + fx * ((c10 >> 8) & 0xFF)) +
            fy * ((1 - fx) * ((c01 >> 8) & 0xFF) + fx * ((c11 >> 8) & 0xFF)));
        int b = (int) Math.round(
            (1 - fy) * ((1 - fx) * (c00 & 0xFF) + fx * (c10 & 0xFF)) +
            fy * ((1 - fx) * (c01 & 0xFF) + fx * (c11 & 0xFF)));

        return (r << 16) | (g << 8) | b;
    }

    /**
     * Resizes a text crop to height 48 (preserving aspect ratio) and normalizes
     * pixel values to [-1, 1] for CRNN input.
     */
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

    /**
     * CTC greedy decoding: argmax at each timestep, collapsing repeats and
     * removing blanks (index 0). Whitespace is trimmed from the final string.
     */
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
