package com.trulyfreeocr.util;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Utility for running external processes and capturing stdout/stderr.
 *
 * Two convenience modes:
 *   run(executable, args...)      — blocks until completion (no timeout).
 *   run(executable, args, ms)     — kills the process after <ms> milliseconds.
 *
 * Stderr is NOT merged with stdout (redirectErrorStream = false) so
 * callers can distinguish normal output from error diagnostics.
 */
public class SubprocessRunner {

    /**
     * Executes a command with no timeout.
     *
     * @param executable  The binary to run.
     * @param args        Variable-length argument list.
     * @return Result containing exit code, stdout, and stderr.
     */
    public static Result run(File executable, String... args) throws IOException {
        return run(executable, List.of(args), -1);
    }

    /**
     * Executes a command with an optional timeout.
     *
     * @param executable  The binary to run.
     * @param args        Argument list.
     * @param timeoutMs   Max wall-clock time in milliseconds (&le;0 = no timeout).
     * @return Result containing exit code, stdout, and stderr.
     */
    public static Result run(File executable, List<String> args, long timeoutMs) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(executable.getAbsolutePath());
        command.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);

        Process process = pb.start();

        // Drain stdout/stderr in daemon threads to prevent pipe-buffer deadlock
        ByteArrayOutputStream stdoutBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream stderrBuf = new ByteArrayOutputStream();
        Thread outDrainer = new Thread(() -> drain(process.getInputStream(), stdoutBuf), "drain-stdout");
        Thread errDrainer = new Thread(() -> drain(process.getErrorStream(), stderrBuf), "drain-stderr");
        outDrainer.setDaemon(true);
        errDrainer.setDaemon(true);
        outDrainer.start();
        errDrainer.start();

        try {
            if (timeoutMs > 0) {
                boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    throw new IOException("Process timed out after " + timeoutMs + "ms: " + executable.getName());
                }
            } else {
                try {
                    process.waitFor();
                } catch (InterruptedException e) {
                    process.destroyForcibly();
                    Thread.currentThread().interrupt();
                    throw new IOException("Process was interrupted", e);
                }
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Process was interrupted", e);
        }

        try { outDrainer.join(5000); } catch (InterruptedException ignored) { }
        try { errDrainer.join(5000); } catch (InterruptedException ignored) { }

        int exitCode = process.exitValue();
        return new Result(exitCode, stdoutBuf.toByteArray(), stderrBuf.toByteArray());
    }

    private static void drain(InputStream is, ByteArrayOutputStream buf) {
        try (is) {
            byte[] tmp = new byte[4096];
            int n;
            while ((n = is.read(tmp)) != -1) {
                buf.write(tmp, 0, n);
            }
        } catch (IOException ignored) { }
    }

    /**
     * Holds the subprocess output.
     *
     * exitCode  Process exit status (0 = success).
     * stdout    Raw bytes written to standard output.
     * stderr    Raw bytes written to standard error.
     */
    public static class Result {
        private final int exitCode;
        private final byte[] stdout;
        private final byte[] stderr;

        public Result(int exitCode, byte[] stdout, byte[] stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        public int getExitCode() { return exitCode; }
        public byte[] getStdout() { return stdout; }
        public byte[] getStderr() { return stderr; }
        public String getStdoutString() { return new String(stdout); }
        public String getStderrString() { return new String(stderr); }
    }
}
