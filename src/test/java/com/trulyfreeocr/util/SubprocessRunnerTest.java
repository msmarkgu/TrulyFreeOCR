package com.trulyfreeocr.util;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;

import org.junit.jupiter.api.Test;

/**
 * Tests for SubprocessRunner using /bin/sh as a known-available executable.
 *
 * Verifies stdout capture, non-zero exit code reporting, and stderr separation
 * (redirectErrorStream = false).
 */
class SubprocessRunnerTest {

    @Test
    void run_echo_returnsStdout() throws IOException {
        SubprocessRunner.Result result = SubprocessRunner.run(
                new File("/bin/sh"), "-c", "echo hello");
        assertEquals(0, result.getExitCode());
        assertTrue(result.getStdoutString().contains("hello"));
    }

    @Test
    void run_failingCommand_returnsNonZero() throws IOException {
        SubprocessRunner.Result result = SubprocessRunner.run(
                new File("/bin/sh"), "-c", "exit 42");
        assertEquals(42, result.getExitCode());
    }

    @Test
    void run_stderr_isCaptured() throws IOException {
        SubprocessRunner.Result result = SubprocessRunner.run(
                new File("/bin/sh"), "-c", "echo error >&2");
        assertEquals(0, result.getExitCode());
        assertTrue(result.getStderrString().contains("error"));
    }

    /**
     * Regression test for the pipe-buffer deadlock bug.
     *
     * The old code called process.waitFor() before reading stdout/stderr.
     * If the subprocess filled the OS pipe buffer (~4-64 KB) the subprocess
     * would block forever waiting for the parent to read, while the parent
     * was blocked on waitFor() — a permanent deadlock.
     *
     * The fix drains stdout/stderr in daemon threads before waitFor().
     * This test produces >64 KB of output to force the deadlock condition.
     */
    @Test
    void run_largeStdout_doesNotDeadlock() throws IOException {
        // Generate ~100 KB of output via shell loop
        SubprocessRunner.Result result = SubprocessRunner.run(
                new File("/bin/sh"), "-c",
                "for i in $(seq 1 2000); do printf 'line-%04d-abcdefghijklmnopqrstuvwxyz0123456789\\n' $i; done");
        assertEquals(0, result.getExitCode());
        assertTrue(result.getStdout().length > 65536,
            "Should produce >64KB output to verify pipe-buffer deadlock is fixed");
        // First line should be "line-0001-..."
        assertTrue(result.getStdoutString().startsWith("line-0001-"),
            "Stdout should start with first line");
    }
}
