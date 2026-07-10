package com.trulyfreeocr.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

class SettingsTest {

    @Test
    void parseJsonc_handlesComments() {
        Map<String, String> v = Settings.parseJsonc(
            "{ /* block */ \"x\": \"y\", // line\n  \"n\": 1, \"f\": 2.5, \"b\": true }");
        assertEquals("y", v.get("x"));
        assertEquals("1", v.get("n"));
        assertEquals("2.5", v.get("f"));
        assertEquals("true", v.get("b"));
    }

    @Test
    void parseJsonc_emptyObject_returnsEmpty() {
        assertTrue(Settings.parseJsonc("{}").isEmpty());
    }

    @Test
    void parseJsonc_ignoresCommentMarkersInsideStringValues() {
        Map<String, String> v1 = Settings.parseJsonc(
            "{ \"path\": \"//server/share/tessdata\" }");
        assertEquals("//server/share/tessdata", v1.get("path"),
            "// inside a string value should not be stripped as a line comment");

        Map<String, String> v2 = Settings.parseJsonc(
            "{ \"url\": \"https://example.com/api\" }");
        assertEquals("https://example.com/api", v2.get("url"),
            "https:// should not be stripped as a line comment");

        Map<String, String> v3 = Settings.parseJsonc(
            "{ \"sql\": \"SELECT * FROM t WHERE x /* comment */ = 1\" }");
        assertEquals("SELECT * FROM t WHERE x /* comment */ = 1", v3.get("sql"),
            "/* inside a string value should not be stripped as a block comment");
    }

    @Test
    void parseJsonc_loadsActualFile() {
        Settings s = Settings.load();
        assertEquals("./deps/tesseract/linux/tesseract", s.getString("tesseract.path", "DEFAULT"));
        assertEquals("./deps/tesseract/tessdata", s.getString("tessdata.dir", "DEFAULT"));
        assertEquals(300, s.getInt("rendering.dpi", 0));
        assertEquals(0.95, s.getDouble("segmenter.percentile", 0), 1e-9);
        assertEquals(64, s.getInt("segmenter.tileSize", 0));
        assertEquals(3, s.getInt("segmenter.inpaintRadius", 0));
        assertEquals("-p -s", s.getString("jbig2enc.flags", "DEFAULT"));
        assertEquals("HELVETICA", s.getString("pdf.font", "DEFAULT"));
    }
}
