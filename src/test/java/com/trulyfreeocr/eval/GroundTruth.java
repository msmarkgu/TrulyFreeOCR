package com.trulyfreeocr.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the ground-truth JSON files produced by generate_eval_corpus.py.
 *
 * Format:
 *   {"source":"...","title":"...","pages":[{"page":1,"words":["a","b",...]},...]}
 */
public class GroundTruth {

    private final String source;
    private final String title;
    private final List<List<String>> pages; // page index → list of words

    public GroundTruth(String source, String title, List<List<String>> pages) {
        this.source = source;
        this.title = title;
        this.pages = pages;
    }

    public String getSource() { return source; }
    public String getTitle() { return title; }
    public int getPageCount() { return pages.size(); }
    public List<String> getWords(int page) { return pages.get(page); }

    public static GroundTruth load(Path jsonPath) throws IOException {
        String raw = Files.readString(jsonPath);
        Parser p = new Parser(raw);
        return p.parse();
    }

    // ── minimal recursive-descent JSON parser ──────────────────────────

    private static class Parser {
        private final String s;
        private int pos;

        Parser(String s) { this.s = s; this.pos = 0; }

        GroundTruth parse() {
            expect('{');
            String src = null, title = null;
            List<List<String>> pages = null;
            skipWs();

            while (pos < s.length() && s.charAt(pos) != '}') {
                String key = parseString();
                skipWs();
                expect(':');
                skipWs();
                switch (key) {
                    case "source" -> src = parseString();
                    case "title" -> title = parseString();
                    case "pages" -> pages = parsePages();
                    default -> skipValue();
                }
                skipWs();
                skip(',');
                skipWs();
            }
            return new GroundTruth(src, title, pages);
        }

        private List<List<String>> parsePages() {
            List<List<String>> result = new ArrayList<>();
            expect('[');
            skipWs();
            while (pos < s.length() && s.charAt(pos) != ']') {
                result.add(parsePage());
                skipWs();
                skip(',');
                skipWs();
            }
            expect(']');
            return result;
        }

        private List<String> parsePage() {
            List<String> words = null;
            expect('{');
            skipWs();
            while (pos < s.length() && s.charAt(pos) != '}') {
                String key = parseString();
                skipWs();
                expect(':');
                skipWs();
                if (key.equals("words")) {
                    words = parseStringArray();
                } else {
                    skipValue();
                }
                skipWs();
                skip(',');
                skipWs();
            }
            expect('}');
            return words;
        }

        private List<String> parseStringArray() {
            List<String> result = new ArrayList<>();
            expect('[');
            skipWs();
            while (pos < s.length() && s.charAt(pos) != ']') {
                result.add(parseString());
                skipWs();
                skip(',');
                skipWs();
            }
            expect(']');
            return result;
        }

        private String parseString() {
            skipWs();
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < s.length() && s.charAt(pos) != '"') {
                if (s.charAt(pos) == '\\') {
                    pos++;
                    if (pos < s.length()) sb.append(s.charAt(pos++));
                } else {
                    sb.append(s.charAt(pos++));
                }
            }
            expect('"');
            return sb.toString();
        }

        private void skipValue() {
            skipWs();
            if (pos >= s.length()) return;
            char c = s.charAt(pos);
            if (c == '"') { parseString(); return; }
            if (c == '[' || c == '{') {
                int depth = 1;
                pos++;
                while (pos < s.length() && depth > 0) {
                    if (s.charAt(pos) == '"') { parseString(); continue; }
                    if (s.charAt(pos) == '[' || s.charAt(pos) == '{') depth++;
                    if (s.charAt(pos) == ']' || s.charAt(pos) == '}') depth--;
                    pos++;
                }
                return;
            }
            while (pos < s.length() && s.charAt(pos) != ',' && s.charAt(pos) != '}' && s.charAt(pos) != ']') pos++;
        }

        private void skip(char c) {
            skipWs();
            if (pos < s.length() && s.charAt(pos) == c) pos++;
        }

        private void expect(char c) {
            skipWs();
            if (pos >= s.length() || s.charAt(pos) != c)
                throw new RuntimeException("Expected '" + c + "' at pos " + pos + " got '" + (pos < s.length() ? s.charAt(pos) : "EOF") + "'");
            pos++;
        }

        private void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }
    }
}
