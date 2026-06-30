package com.trulyfreeocr.util;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * Tests for TempFileManager.
 *
 * Verifies that createTempFile creates a writable file with the expected
 * prefix/suffix, and that cleanup() successfully deletes tracked files.
 */
class TempFileManagerTest {

    @Test
    void createTempFile_createsWritableFile() throws IOException {
        var manager = new TempFileManager();
        File f = manager.createTempFile("test", ".png");
        assertTrue(f.exists());
        assertTrue(f.canWrite());
        assertTrue(f.getName().startsWith("test"));
        assertTrue(f.getName().endsWith(".png"));
        manager.cleanup();
        assertFalse(f.exists());
    }
}
