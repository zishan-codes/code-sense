package com.sce.core;

import java.util.Objects;

/**
 * EvaluationRequest
 *
 * Immutable value object representing a single code-evaluation request
 * submitted by the AI UI Controller to the pipeline (I/O Manager ->
 * Concurrency Engine). Being immutable, instances of this class are
 * inherently thread-safe and can be freely passed between the worker
 * threads in the Concurrency Engine without external synchronization.
 *
 * Design note: fields are declared 'final' and the class itself is
 * declared 'final' to prevent subclassing from reintroducing mutability.
 *
 * @author Smart Code Evaluator Team
 */
public final class EvaluationRequest {

    /**
     * The public class name declared in the submitted source code.
     * This MUST exactly match the public class in sourceCode, since
     * javac enforces filename == public-class-name for compilation.
     */
    private final String className;

    /**
     * The raw, unmodified Java source code submitted by the user.
     */
    private final String sourceCode;

    /**
     * Constructs an immutable EvaluationRequest.
     *
     * @param className  the public class name in the submitted source;
     *                    must not be null or blank
     * @param sourceCode the full Java source code to be evaluated;
     *                    must not be null or blank
     * @throws IllegalArgumentException if either argument is null, empty,
     *                                    or consists only of whitespace
     */
    public EvaluationRequest(String className, String sourceCode) {
        if (className == null || className.trim().isEmpty()) {
            throw new IllegalArgumentException(
                "className must not be null or blank — the I/O Manager " +
                "requires a valid class name to derive the .java filename."
            );
        }
        if (sourceCode == null || sourceCode.trim().isEmpty()) {
            throw new IllegalArgumentException(
                "sourceCode must not be null or blank — nothing to evaluate."
            );
        }
        this.className = className.trim();
        this.sourceCode = sourceCode;
    }

    /**
     * @return the public class name associated with this request.
     */
    public String getClassName() {
        return className;
    }

    /**
     * @return the raw Java source code associated with this request.
     */
    public String getSourceCode() {
        return sourceCode;
    }

    @Override
    public String toString() {
        // sourceCode is deliberately truncated in toString() to keep
        // log lines readable; full source should be accessed via getSourceCode().
        int previewLength = Math.min(sourceCode.length(), 40);
        return "EvaluationRequest{" +
                "className='" + className + '\'' +
                ", sourceCodePreview='" + sourceCode.substring(0, previewLength)
                        .replace("\n", "\\n") + "...'" +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EvaluationRequest)) return false;
        EvaluationRequest that = (EvaluationRequest) o;
        return className.equals(that.className) && sourceCode.equals(that.sourceCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(className, sourceCode);
    }
}