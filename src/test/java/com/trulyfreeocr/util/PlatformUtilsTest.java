package com.trulyfreeocr.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Smoke tests for PlatformUtils.
 *
 * Verifies that OS/arch detection returns non-null values and that
 * getProjectJavaHome() returns a non-null path (either from the
 * TFOCR_JAVA_HOME env var or the running JVM's java.home).
 */
class PlatformUtilsTest {

    @Test
    void detectOs_returnsNonNull() {
        assertNotNull(PlatformUtils.detectOs());
    }

    @Test
    void detectArch_returnsNonNull() {
        assertNotNull(PlatformUtils.detectArch());
    }

    @Test
    void getProjectJavaHome_returnsNonNullWhenSet() {
        // TFOCR_JAVA_HOME might not be set in test env; this just verifies the API
        String home = PlatformUtils.getProjectJavaHome();
        assertNotNull(home);
    }
}
