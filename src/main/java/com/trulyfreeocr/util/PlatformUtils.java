package com.trulyfreeocr.util;

import java.io.File;

/**
 * Cross-platform detection utilities.
 *
 * Provides consistent enum values for OS and CPU architecture,
 * and resolves the project JDK location via the TFOCR_JAVA_HOME
 * environment variable (falling back to the running JVM's java.home).
 *
 * OS detection is based on the os.name system property:
 *   "win" in any case   → Os.WINDOWS
 *   "mac" in any case   → Os.MAC
 *   anything else       → Os.LINUX
 *
 * Architecture detection is based on os.arch:
 *   "aarch64" or "arm64" → Arch.AARCH64
 *   anything else         → Arch.X64
 */
public class PlatformUtils {

    public enum Os { WINDOWS, LINUX, MAC }

    public enum Arch { X64, AARCH64 }

    public static Os detectOs() {
        String raw = System.getProperty("os.name").toLowerCase();
        if (raw.contains("win")) return Os.WINDOWS;
        if (raw.contains("mac")) return Os.MAC;
        return Os.LINUX;
    }

    public static Arch detectArch() {
        String raw = System.getProperty("os.arch").toLowerCase();
        if (raw.contains("aarch64") || raw.contains("arm64")) return Arch.AARCH64;
        return Arch.X64;
    }

    /**
     * Returns the project-specific JDK home.
     *
     * Checks in order:
     *   1. TFOCR_JAVA_HOME environment variable (set by run.sh/bootstrap.sh)
     *   2. deps/jdk/ directory in the project root (project-local install)
     *   3. The JVM that is running this code (java.home system property)
     */
    public static String getProjectJavaHome() {
        String env = System.getenv("TFOCR_JAVA_HOME");
        if (env != null && !env.isEmpty()) return env;
        File projectJdk = new File("deps/jdk");
        if (projectJdk.isDirectory() && new File(projectJdk, "bin/java").canExecute()) {
            return projectJdk.getAbsolutePath();
        }
        return System.getProperty("java.home");
    }
}
