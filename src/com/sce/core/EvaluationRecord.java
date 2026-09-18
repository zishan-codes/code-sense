package com.sce.core;

import java.time.Instant;
import java.util.Objects;

/**
 * EvaluationRecord
 *
 * Immutable, unified snapshot of a single completed evaluation, combining
 * the original request, its final classified outcome, captured process
 * output, the AI diagnostic response (if one was requested), and timing
 * metadata — assembled by SystemController once an evaluation reaches a
 * terminal state.
 *
 * This class deliberately holds no references to CompileResult, ExecResult,
 * or AIResponse. Instead, SystemController copies the specific values it
 * needs out of those objects at construction time. This keeps
 * com.sce.core free of any dependency on com.sce.engine's or com.sce.ai's
 * internal result shapes, so a future refactor of those classes cannot
 * force a change here, and this class can later be serialized (e.g. to
 * JSON for a REST API, or to a SQLite row) without dragging in unrelated
 * engine-internal types.
 *
 * Classification reuses the existing Result.Status enum
 * (SUCCESS, COMPILE_ERROR, RUNTIME_ERROR, TIMEOUT) rather than modifying
 * com.sce.engine.Stage, whose existing meaning is strictly "the pipeline
 * stage at which a failure occurred" — SUCCESS is not a failure stage,
 * and overloading Stage with it would change semantics that
 * EvaluationException and AIConnector's prompt routing already depend on.
 *
 * Immutability is a deliberate safety property, not a style preference:
 * this object may be read by the worker thread that constructs it, by a
 * Swing callback dispatched onto the Event Dispatch Thread, and — in a
 * future phase — by a persistence layer or an HTTP handler thread. Making
 * every field final and exposing no setters means all of those readers
 * can safely share the same instance with no synchronization required.
 *
 * @author Smart Code Evaluator Team
 */
public final class EvaluationRecord {

    /**
     * AiRequestStatus
     *
     * Distinguishes "the AI was never called" from "the AI was called but
     * failed to produce a usable response" — a distinction a single
     * boolean cannot represent without an invalid state becoming
     * expressible. SUCCESS-stage evaluations never invoke AIConnector
     * (matching SystemController's existing behavior), which is exactly
     * the case NOT_REQUESTED exists to capture.
     */
    public enum AiRequestStatus {
        /** No AI call was made for this evaluation (e.g. it succeeded). */
        NOT_REQUESTED,

        /** AIConnector was called and returned a usable hint. */
        AVAILABLE,

        /** AIConnector was called but failed (network, missing key,
         *  malformed reply, rate limit, etc.) — matches
         *  AIResponse.isSuccess() == false. */
        UNAVAILABLE
    }

    /**
     * Reserved sentinel exit code for evaluations that never reached the
     * execution stage at all (i.e. compilation failed). Distinct from
     * {@link com.sce.engine.ExecResult#TIMEOUT_EXIT_CODE} (-1), which
     * represents "execution started but was forcibly terminated."
     * CompileResult carries no OS-level exit code of its own, so this
     * value exists purely to keep exitCode a meaningful, always-populated
     * field on every EvaluationRecord rather than leaving it undefined
     * for the compile-failure case.
     */
    public static final int COMPILE_FAILURE_EXIT_CODE = -2;

    private final String evaluationId;
    private final Instant timestamp;
    private final String className;
    private final String sourceCode;
    private final Result.Status status;
    private final int exitCode;
    private final String stdout;
    private final String stderr;
    private final String aiHint;
    private final String aiSuggestedFix;
    private final AiRequestStatus aiRequestStatus;
    private final long durationMillis;

    /**
     * Constructs an immutable EvaluationRecord. All String fields must be
     * non-null (use an empty string, never null, to represent "no
     * content") — consistent with the null-free convention already
     * established by EvaluationRequest, Result, and AIResponse.
     *
     * @param evaluationId     a unique, stable identifier for this
     *                          evaluation, generated once at construction
     *                          time (not a database-assigned key), so the
     *                          same identity survives from live UI display
     *                          through eventual persistence and API
     *                          exposure
     * @param timestamp         the instant this evaluation completed
     * @param className         the public class name that was evaluated
     * @param sourceCode        the full source code that was submitted
     * @param status             the terminal classification of this
     *                            evaluation
     * @param exitCode           the OS-level process exit code; -1 for a
     *                            watchdog-enforced timeout (per
     *                            ExecResult's existing convention), or
     *                            {@link #COMPILE_FAILURE_EXIT_CODE} (-2)
     *                            if compilation failed before execution
     *                            was ever attempted
     * @param stdout             captured standard output, or an empty
     *                            string if none was produced or execution
     *                            never occurred
     * @param stderr             captured standard error / compiler
     *                            diagnostic text, or an empty string if
     *                            none was produced
     * @param aiHint             the AI-generated root-cause explanation,
     *                            or an empty string if aiRequestStatus is
     *                            NOT_REQUESTED
     * @param aiSuggestedFix     the AI-generated short correction pointer,
     *                            or an empty string if none was offered or
     *                            aiRequestStatus is NOT_REQUESTED
     * @param aiRequestStatus    whether the AI was never called, called
     *                            successfully, or called but unavailable
     * @param durationMillis     wall-clock duration of the compile +
     *                            execute portion of the pipeline, in
     *                            milliseconds
     * @throws IllegalArgumentException if any non-primitive argument is
     *                                    null, or if durationMillis is
     *                                    negative
     */
    public EvaluationRecord(String evaluationId,
                             Instant timestamp,
                             String className,
                             String sourceCode,
                             Result.Status status,
                             int exitCode,
                             String stdout,
                             String stderr,
                             String aiHint,
                             String aiSuggestedFix,
                             AiRequestStatus aiRequestStatus,
                             long durationMillis) {

        this.evaluationId = requireNonNull(evaluationId, "evaluationId");
        this.timestamp = requireNonNull(timestamp, "timestamp");
        this.className = requireNonNull(className, "className");
        this.sourceCode = requireNonNull(sourceCode, "sourceCode");
        this.status = requireNonNull(status, "status");
        this.stdout = requireNonNull(stdout, "stdout");
        this.stderr = requireNonNull(stderr, "stderr");
        this.aiHint = requireNonNull(aiHint, "aiHint");
        this.aiSuggestedFix = requireNonNull(aiSuggestedFix, "aiSuggestedFix");
        this.aiRequestStatus = requireNonNull(aiRequestStatus, "aiRequestStatus");

        if (durationMillis < 0) {
            throw new IllegalArgumentException(
                "EvaluationRecord: durationMillis must not be negative (was " + durationMillis + ")."
            );
        }

        this.exitCode = exitCode;
        this.durationMillis = durationMillis;
    }

    private static <T> T requireNonNull(T value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException("EvaluationRecord: " + fieldName + " must not be null.");
        }
        return value;
    }

    /** @return the unique, stable identifier for this evaluation. */
    public String getEvaluationId() {
        return evaluationId;
    }

    /** @return the instant this evaluation completed. */
    public Instant getTimestamp() {
        return timestamp;
    }

    /** @return the public class name that was evaluated. */
    public String getClassName() {
        return className;
    }

    /** @return the full source code that was submitted. */
    public String getSourceCode() {
        return sourceCode;
    }

    /** @return the terminal classification of this evaluation. */
    public Result.Status getStatus() {
        return status;
    }

    /**
     * @return the OS-level exit code, -1 for timeout, or
     *          {@link #COMPILE_FAILURE_EXIT_CODE} if compilation failed.
     */
    public int getExitCode() {
        return exitCode;
    }

    /** @return captured standard output, or an empty string. */
    public String getStdout() {
        return stdout;
    }

    /** @return captured standard error / compiler diagnostics, or an empty string. */
    public String getStderr() {
        return stderr;
    }

    /** @return the AI-generated root-cause explanation, or an empty string. */
    public String getAiHint() {
        return aiHint;
    }

    /** @return the AI-generated short correction pointer, or an empty string. */
    public String getAiSuggestedFix() {
        return aiSuggestedFix;
    }

    /** @return whether the AI was never called, succeeded, or was unavailable. */
    public AiRequestStatus getAiRequestStatus() {
        return aiRequestStatus;
    }

    /** @return wall-clock duration of the compile + execute pipeline, in milliseconds. */
    public long getDurationMillis() {
        return durationMillis;
    }

    @Override
    public String toString() {
        // Source code and output are deliberately summarized by length
        // rather than printed in full, matching the convention already
        // established by EvaluationRequest, ExecResult, and Result.
        return "EvaluationRecord{" +
                "evaluationId='" + evaluationId + '\'' +
                ", timestamp=" + timestamp +
                ", className='" + className + '\'' +
                ", status=" + status +
                ", exitCode=" + exitCode +
                ", stdoutLength=" + stdout.length() +
                ", stderrLength=" + stderr.length() +
                ", aiRequestStatus=" + aiRequestStatus +
                ", durationMillis=" + durationMillis +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EvaluationRecord)) return false;
        EvaluationRecord that = (EvaluationRecord) o;
        // Identity equality is based on evaluationId alone, consistent
        // with this field's role as a stable, unique identifier — two
        // records with the same ID represent the same evaluation even if
        // read back from different sources (e.g. live object vs. a
        // future DB round-trip).
        return evaluationId.equals(that.evaluationId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(evaluationId);
    }
}