package com.trulyfreeocr.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

public class Settings {

    private static volatile Settings instance;

    private final Map<String, String> values;

    private static final Path DEFAULT_PATH = Paths.get("settings.jsonc");

    // System property key for overriding the settings file location.
    private static final String PROP_KEY = "tfocr.settings";

    public Settings() {
        this.values = new HashMap<>();
    }

    public Settings(Map<String, String> values) {
        this.values = new HashMap<>(values);
    }

    /**
     * Loads settings from the default location (settings.jsonc in CWD),
     * or from the path specified by the "tfocr.settings" system property.
     * Does nothing if the file does not exist — all getters fall back to
     * their supplied defaults.
     */
    public static Settings load() {
        Settings s = new Settings();
        Path path = findPath();
        if (path != null && Files.exists(path)) {
            try {
                String raw = Files.readString(path);
                s.values.putAll(parseJsonc(raw));
            } catch (IOException e) {
                // file missing or unreadable — use defaults
            }
        }
        instance = s;
        return s;
    }

    /**
     * Returns the singleton instance.  Must be preceded by a call to load().
     */
    public static Settings getInstance() {
        if (instance == null) {
            instance = new Settings();
        }
        return instance;
    }

    // ---- typed accessors with defaults ----

    public String getString(String key, String defaultValue) {
        return values.getOrDefault(key, defaultValue);
    }

    public int getInt(String key, int defaultValue) {
        String v = values.get(key);
        if (v == null) return defaultValue;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return defaultValue; }
    }

    public double getDouble(String key, double defaultValue) {
        String v = values.get(key);
        if (v == null) return defaultValue;
        try { return Double.parseDouble(v); } catch (NumberFormatException e) { return defaultValue; }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String v = values.get(key);
        if (v == null) return defaultValue;
        return "true".equalsIgnoreCase(v);
    }

    // ---- path resolution ----

    private static Path findPath() {
        String prop = System.getProperty(PROP_KEY);
        if (prop != null && !prop.isEmpty()) {
            return Paths.get(prop);
        }
        return DEFAULT_PATH;
    }

    // ---- minimal JSONC parser (flat objects only) ----

    static Map<String, String> parseJsonc(String raw) {
        String stripped = stripComments(raw);
        return parseJson(stripped);
    }

    private static String stripComments(String input) {
        StringBuilder out = new StringBuilder(input.length());
        int i = 0;
        while (i < input.length()) {
            char c = input.charAt(i);

            // Skip over string literals so comment markers inside values aren't stripped
            if (c == '"') {
                out.append(c);
                i++;
                while (i < input.length()) {
                    char sc = input.charAt(i);
                    out.append(sc);
                    i++;
                    if (sc == '\\' && i < input.length()) {
                        out.append(input.charAt(i));
                        i++;
                    } else if (sc == '"') {
                        break;
                    }
                }
                continue;
            }

            if (i + 1 < input.length() && c == '/' && input.charAt(i + 1) == '/') {
                // line comment — skip to end of line
                i += 2;
                while (i < input.length() && input.charAt(i) != '\n') i++;
                continue;
            }
            if (i + 1 < input.length() && c == '/' && input.charAt(i + 1) == '*') {
                // block comment — skip to */
                i += 2;
                while (i + 1 < input.length() && !(input.charAt(i) == '*' && input.charAt(i + 1) == '/')) i++;
                i += 2; // skip */
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static Map<String, String> parseJson(String input) {
        Map<String, String> result = new HashMap<>();
        int i = skipWhitespace(input, 0);
        if (i >= input.length() || input.charAt(i) != '{') return result;
        i = skipWhitespace(input, i + 1);

        while (i < input.length()) {
            if (input.charAt(i) == '}') break;
            if (input.charAt(i) == ',') { i = skipWhitespace(input, i + 1); continue; }

            // parse key
            if (input.charAt(i) != '"') break;
            int keyStart = i + 1;
            int keyEnd = findStringEnd(input, keyStart);
            if (keyEnd < 0) break;
            String key = input.substring(keyStart, keyEnd);
            i = skipWhitespace(input, keyEnd + 1);

            if (i >= input.length() || input.charAt(i) != ':') break;
            i = skipWhitespace(input, i + 1);

            // parse value
            if (i >= input.length()) break;
            char c = input.charAt(i);
            String value;
            if (c == '"') {
                int valStart = i + 1;
                int valEnd = findStringEnd(input, valStart);
                if (valEnd < 0) break;
                value = input.substring(valStart, valEnd);
                i = skipWhitespace(input, valEnd + 1);
            } else if (c == 't' && input.startsWith("true", i)) {
                value = "true";
                i = skipWhitespace(input, i + 4);
            } else if (c == 'f' && input.startsWith("false", i)) {
                value = "false";
                i = skipWhitespace(input, i + 5);
            } else if (c == 'n' && input.startsWith("null", i)) {
                value = "null";
                i = skipWhitespace(input, i + 4);
            } else {
                // number
                int valEnd = i;
                while (valEnd < input.length() && (Character.isDigit(input.charAt(valEnd))
                        || input.charAt(valEnd) == '.' || input.charAt(valEnd) == '-'
                        || input.charAt(valEnd) == '+' || input.charAt(valEnd) == 'e'
                        || input.charAt(valEnd) == 'E')) {
                    valEnd++;
                }
                if (valEnd == i) break;
                value = input.substring(i, valEnd);
                i = skipWhitespace(input, valEnd);
            }
            result.put(key, value);
        }
        return result;
    }

    private static int skipWhitespace(String s, int pos) {
        while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        return pos;
    }

    private static int findStringEnd(String s, int start) {
        int i = start;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2; // skip escaped char
            } else if (c == '"') {
                return i;
            } else {
                i++;
            }
        }
        return -1;
    }
}
