package com.sce.engine;

/**
 * EvaluationException
 *
 * Unchecked exception representing a failure encountered by the
 * Concurrency Engine itself while attempting to compile or execute
 * submitted code — as distinct from a COMPILE_ERROR or RUNTIME_ERROR
 * Result, which represent the *student's code* failing normally.
 *
 * In other words: a non-zero javac exit code is expected, routine
 * behavior and is represented as a CompileResult(false, ...) — it does
 * NOT throw this exception. EvaluationException is reserved for
 * abnormal engine-level failures: the javac/java binary could not be
 * launched at all, a process stream could not be drained, or a worker
 * thread was interrupted mid-operation. This separation keeps the
 * "student code is broken" path and the "our tooling is broken" path
 * from being conflated at the call site.
 *
 * Declared as a RuntimeException (unchecked) because these failures are
 * almost always unrecoverable at the immediate call site and forcing a
 * checked-exception signature through every method in the Concurrency
 * Engine, the AI UI Controller, and the AI Connector would add
 * boilerplate without adding safety — callers that DO want to handle
 * it explicitly (e.g., to render an "environment error" banner) remain
 * free to catch EvaluationException specifically.
 *
 * @author Smart Code Evaluator Team
 */
public class EvaluationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Stage stage;
    private final String rawTrace;

    /**
     * Constructs an EvaluationException tied to a specific pipeline stage.
     *
     * @param stage    the pipeline stage during which the failure occurred;
     *                  must not be null
     * @param rawTrace  raw diagnostic text (e.g., the underlying exception's
     *                   message, or a native stack trace fragment) describing
     *                   what went wrong; used both as the exception message
     *                   and as forensic context for logging. Must not be null.
     * @param cause     the underlying Throwable that triggered this failure,
     *                   or null if there is no underlying cause (e.g., a
     *                   defensive validation failure raised directly by the
     *                   engine itself)
     * @throws IllegalArgumentException if stage or rawTrace is null
     */
    public EvaluationException(Stage stage, String rawTrace, Throwable cause) {
        super(rawTrace, cause);
        if (stage == null) {
            throw new IllegalArgumentException("EvaluationException: stage must not be null.");
        }
        if (rawTrace == null) {
            throw new IllegalArgumentException("EvaluationException: rawTrace must not be null.");
        }
        this.stage = stage;
        this.rawTrace = rawTrace;
    }

    /**
     * Convenience overload for failures with no underlying Throwable cause
     * (e.g., a defensive check failing before any external process was
     * even attempted).
     *
     * @param stage    the pipeline stage during which the failure occurred
     * @param rawTrace  diagnostic text describing what went wrong
     */
    public EvaluationException(Stage stage, String rawTrace) {
        this(stage, rawTrace, null);
    }

    /** @return the pipeline stage during which this failure occurred. */
    public Stage getStage() {
        return stage;
    }

    /** @return the raw diagnostic text captured at the point of failure. */
    public String getRawTrace() {
        return rawTrace;
    }
}