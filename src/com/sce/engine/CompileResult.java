package com.sce.engine;

/**
 * CompileResult
 *
 * Immutable POJO capturing the outcome of a single javac invocation.
 * Deliberately simpler than ExecResult: javac's stdout and stderr are
 * combined into a single 'output' field, since the distinction between
 * the two streams carries no useful signal at the compilation stage —
 * a caller only needs to know whether compilation succeeded, and if
 * not, the diagnostic text explaining why.
 *
 * @author Smart Code Evaluator Team
 */
public final class CompileResult {

    private final boolean isSuccessful;
    private final String output;

    /**
     * Constructs an immutable CompileResult.
     *
     * @param isSuccessful true if javac exited with status code 0
     * @param output        combined stdout + stderr text from the javac
     *                       invocation; must not be null (pass an empty
     *                       string, never null, if there was no output)
     * @throws IllegalArgumentException if output is null
     */
    public CompileResult(boolean isSuccessful, String output) {
        if (output == null) {
            throw new IllegalArgumentException(
                "CompileResult: output must not be null — pass an empty " +
                "string to represent 'no compiler output'."
            );
        }
        this.isSuccessful = isSuccessful;
        this.output = output;
    }

    /** @return true if compilation completed with exit code 0. */
    public boolean isSuccessful() {
        return isSuccessful;
    }

    /** @return the combined stdout/stderr text produced by javac. */
    public String getOutput() {
        return output;
    }

    @Override
    public String toString() {
        return "CompileResult{isSuccessful=" + isSuccessful +
                ", outputLength=" + output.length() + '}';
    }
}