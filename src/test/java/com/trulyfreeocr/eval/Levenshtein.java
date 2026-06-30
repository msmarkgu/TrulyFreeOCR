package com.trulyfreeocr.eval;

import java.util.List;

public class Levenshtein {

    public static int distance(List<String> a, List<String> b) {
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

    public static int distanceChars(String a, String b) {
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

    public static double wer(List<String> hypothesis, List<String> reference) {
        if (reference.isEmpty()) return hypothesis.isEmpty() ? 0 : 1;
        return (double) distance(hypothesis, reference) / reference.size();
    }

    public static double cer(String hypothesis, String reference) {
        if (reference.isEmpty()) return hypothesis.isEmpty() ? 0 : 1;
        return (double) distanceChars(hypothesis, reference) / reference.length();
    }
}
