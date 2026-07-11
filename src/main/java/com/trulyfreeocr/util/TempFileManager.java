package com.trulyfreeocr.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Tracks temporary files created during a pipeline run and provides
 * a single cleanup() call to delete them all.
 *
 * Each file is also registered with deleteOnExit() as a safety net
 * in case cleanup() is not called (e.g., JVM crash).
 */
public class TempFileManager {

    private final List<File> tempFiles = new ArrayList<>();

    /**
     * Creates a temp file and registers it for cleanup.
     *
     * @param prefix  File name prefix (e.g. "tfocr-").
     * @param suffix  File name suffix (e.g. ".png").
     * @return The created temporary File.
     */
    public File createTempFile(String prefix, String suffix) throws IOException {
        Files.createDirectories(Path.of("temp"));
        File f = Files.createTempFile(Path.of("temp"), prefix, suffix).toFile();
        f.deleteOnExit();
        tempFiles.add(f);
        return f;
    }

    /**
     * Deletes all tracked temp files and clears the internal list.
     */
    public void cleanup() {
        for (File f : tempFiles) {
            if (f.exists()) f.delete();
        }
        tempFiles.clear();
    }
}
