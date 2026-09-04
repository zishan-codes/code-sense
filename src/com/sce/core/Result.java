package com.sce.core;

import java.util.Objects;

/**
 * Result
 *
 * Immutable value object representing the final outcome of an evaluation
 * pipeline run — produced by the Concurrency Engine / I/O Manager and
 * consumed by the AI UI Controller for rendering, and by the AI Connector
 * as the source material for prompt construction on failure paths.
 *
 * @author Smart Code Evaluator Team
 */
public final class Result {

    /**
     * Status
     *
     * Enumerates every terminal state an evaluation can end in. Kept
     * exhaustive and closed (no generic 'UNKNOWN' bucket) so that every
     * consumer of Result is forced, at compile time, to handle each case
     * explicitly — this is what prevents an unhandled evaluation outcome
     * from silently reaching the UI as a blank panel.
     */
    public enum Status {
        /** Compilation and execution both completed with exit code 0. */
        SUCCESS,

        /** javac returned a non-zero exit code; no execution was attempted. */
        COMPILE_ERROR,

        /** Compilation succeeded but the JVM exited with an uncaught exception. */
        RUNTIME_ERROR,

        /** The watchdog thread forcibly terminated the process after the
         *  configured wall-clock deadline was exceeded (e.g., infinite loop). */
        TIMEOUT
    }

    private final Status status;
    private final int exitCode;
    private final String outputOrErrorTrace;

    /**
     * Constructs an immutable Result.
     *
     * @param status               the terminal state of the evaluation; must not be null
     * @param exitCode             the OS-level process exit code. By convention:
     *                              0 for SUCCESS, javac's/JVM's native non-zero code
     *                              for COMPILE_ERROR / RUNTIME_ERROR, and a sentinel
     *                              value of -1 for TIMEOUT (process was killed, not
     *                              exited naturally, so no real exit code exists).
     * @param outputOrErrorTrace   stdout text on SUCCESS, or the captured stderr /
     *                              stack trace text on any failure status; must not
     *                              be null (use an empty string if there is genuinely
     *                              no output, never pass null)
     * @throws IllegalArgumentException if status or outputOrErrorTrace is null
     */
    public Result(Status status, int exitCode, String outputOrErrorTrace) {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null.");
        }
        if (outputOrErrorTrace == null) {
            throw new IllegalArgumentException(
                "outputOrErrorTrace must not be null — pass an empty string " +
                "instead of null to represent 'no output captured'."
            );
        }
        this.status = status;
        this.exitCode = exitCode;
        this.outputOrErrorTrace = outputOrErrorTrace;
    }

    /** @return the terminal status of this evaluation. */
    public Status getStatus() {
        return status;
    }

    /** @return the OS-level process exit code (see constructor doc for conventions). */
    public int getExitCode() {
        return exitCode;
    }

    /** @return stdout on success, or captured stderr/stack trace text on failure. */
    public String getOutputOrErrorTrace() {
        return outputOrErrorTrace;
    }

    /**
     * Convenience method for callers (e.g., AI UI Controller) that need a
     * quick branch without importing the Status enum's constants directly.
     *
     * @return true if status == Status.SUCCESS
     */
    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    @Override
    public String toString() {
        return "Result{" +
                "status=" + status +
                ", exitCode=" + exitCode +
                ", outputOrErrorTraceLength=" + outputOrErrorTrace.length() +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Result)) return false;
        Result result = (Result) o;
        return exitCode == result.exitCode &&
                status == result.status &&
                outputOrErrorTrace.equals(result.outputOrErrorTrace);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, exitCode, outputOrErrorTrace);
    }
}