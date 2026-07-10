import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class EvalOcrRunner {

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Usage: java EvalOcrRunner <ground-truth.txt> <ocr-output.txt>");
            System.exit(1);
        }

        String gtRaw = new String(Files.readAllBytes(Paths.get(args[0])));
        String ocrRaw = new String(Files.readAllBytes(Paths.get(args[1])));

        String gt = normalize(gtRaw);
        String ocr = normalize(ocrRaw);

        List<String> gtWords = tokenize(gt);
        List<String> ocrWords = tokenize(ocr);

        double cer = cer(gt, ocr);
        double wer = wer(gtWords, ocrWords);

        System.out.printf("gt_chars\t%d%n", gt.length());
        System.out.printf("ocr_chars\t%d%n", ocr.length());
        System.out.printf("gt_words\t%d%n", gtWords.size());
        System.out.printf("ocr_words\t%d%n", ocrWords.size());
        System.out.printf("cer\t%.4f%n", cer);
        System.out.printf("wer\t%.4f%n", wer);
    }

    static String normalize(String s) {
        return s.toLowerCase().replaceAll("[\\p{Cc}\\p{Z}]+", " ").trim();
    }

    static List<String> tokenize(String s) {
        String[] parts = s.split("\\s+");
        List<String> out = new ArrayList<String>(parts.length);
        for (String p : parts) {
            String clean = p.replaceAll("[^\\p{L}\\p{N}]", ""); // \\p{L}=any Unicode letter, \\p{N}=any Unicode digit
            if (!clean.isEmpty()) out.add(clean);
        }
        return out;
    }

    // ── Character-level Levenshtein ──────────────────────────────

    static int distanceChars(String a, String b) {
        int m = a.length(), n = b.length();
        if (m < n) { String t = a; a = b; b = t; int tmp = m; m = n; n = tmp; }
        int[] prev = new int[n + 1];
        for (int j = 0; j <= n; j++) prev[j] = j;
        for (int i = 1; i <= m; i++) {
            int[] curr = new int[n + 1];
            curr[0] = i;
            for (int j = 1; j <= n; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(prev[j] + 1,
                          Math.min(curr[j - 1] + 1, prev[j - 1] + cost));
            }
            prev = curr;
        }
        return prev[n];
    }

    static double cer(String hypothesis, String reference) {
        if (reference.isEmpty()) return hypothesis.isEmpty() ? 0 : 1;
        return (double) distanceChars(hypothesis, reference) / reference.length();
    }

    // ── Word-level Levenshtein ───────────────────────────────────

    static int distanceWords(List<String> a, List<String> b) {
        int m = a.size(), n = b.size();
        if (m < n) { List<String> t = a; a = b; b = t; int tmp = m; m = n; n = tmp; }
        int[] prev = new int[n + 1];
        for (int j = 0; j <= n; j++) prev[j] = j;
        for (int i = 1; i <= m; i++) {
            int[] curr = new int[n + 1];
            curr[0] = i;
            for (int j = 1; j <= n; j++) {
                int cost = a.get(i - 1).equals(b.get(j - 1)) ? 0 : 1;
                curr[j] = Math.min(prev[j] + 1,
                          Math.min(curr[j - 1] + 1, prev[j - 1] + cost));
            }
            prev = curr;
        }
        return prev[n];
    }

    static double wer(List<String> hypothesis, List<String> reference) {
        if (reference.isEmpty()) return hypothesis.isEmpty() ? 0 : 1;
        return (double) distanceWords(hypothesis, reference) / reference.size();
    }
}
