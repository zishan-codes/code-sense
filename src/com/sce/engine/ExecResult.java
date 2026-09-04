package com.sce.engine;

/**
 * ExecResult
 *
 * Immutable POJO capturing the outcome of a single 'java' (execution)
 * invocation. Unlike CompileResult, stdout and stderr are kept SEPARATE
 * here: stdout represents the program's legitimate output (what a
 * successful run would show the student), while stderr carries the
 * exception stack trace on failure — a distinction the AI Connector's
 * prompt builder relies on to extract the failure signal cleanly rather
 * than parsing it back out of interleaved text.
 *
 * IMPORTANT CONVENTION: an exitCode value of exactly -1 is reserved,
 * system-wide, to mean "the watchdog thread forcibly terminated this
 * process due to timeout." It never represents a naturally-occurring
 * JVM exit code. Every consumer of ExecResult (Result mapping, AI
 * Connector, UI rendering) must treat -1 as TIMEOUT, not as a generic
 * runtime failure.
 *
 * @author Smart Code Evaluator Team
 */
public final class ExecResult {

    /** Reserved sentinel exit code meaning "terminated by watchdog on timeout." */
    public static final int TIMEOUT_EXIT_CODE = -1;

    private final int exitCode;
    private final String stdout;
    private final String stderr;

    /**
     * Constructs an immutable ExecResult.
     *
     * @param exitCode the OS-level process exit code, or {@link #TIMEOUT_EXIT_CODE}
     *                  (-1) if the watchdog thread forcibly terminated the process
     * @param stdout    captured standard output text; must not be null
     * @param stderr    captured standard error text; must not be null
     * @throws IllegalArgumentException if stdout or stderr is null
     */
    public ExecResult(int exitCode, String stdout, String stderr) {
        if (stdout == null) {
            throw new IllegalArgumentException("ExecResult: stdout must not be null.");
        }
        if (stderr == null) {
            throw new IllegalArgumentException("ExecResult: stderr must not be null.");
        }
        this.exitCode = exitCode;
        this.stdout = stdout;
        this.stderr = stderr;
    }

    /** @return the process exit code, or {@link #TIMEOUT_EXIT_CODE} if timed out. */
    public int getExitCode() {
        return exitCode;
    }

    /** @return captured standard output text. */
    public String getStdout() {
        return stdout;
    }

    /** @return captured standard error text (typically the stack trace on failure). */
    public String getStderr() {
        return stderr;
    }

    /**
     * Convenience check so callers do not need to hardcode the -1 sentinel
     * inline at every call site.
     *
     * @return true if this ExecResult represents a watchdog-enforced timeout
     */
    public boolean isTimeout() {
        return exitCode == TIMEOUT_EXIT_CODE;
    }

    @Override
    public String toString() {
        return "ExecResult{" +
                "exitCode=" + exitCode +
                ", stdoutLength=" + stdout.length() +
                ", stderrLength=" + stderr.length() +
                '}';
    }
}