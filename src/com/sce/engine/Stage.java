package com.sce.engine;

/**
 * Stage
 *
 * Enumerates the pipeline stage at which an evaluation failure occurred.
 * This is carried on EvaluationException so that upstream consumers
 * (the AI UI Controller, and eventually the AI Connector's prompt
 * builder) can branch on *where* things went wrong without having to
 * parse or guess from exception message text.
 *
 * Kept as a small, closed enum deliberately — adding a new failure
 * class should be a conscious, compile-time-visible decision, not an
 * ad hoc string value threaded through the system.
 *
 * @author Smart Code Evaluator Team
 */
public enum Stage {

    /** Failure occurred while invoking javac (syntax/type errors, javac itself
     *  could not be started, etc.). No execution was attempted. */
    COMPILE,

    /** Compilation succeeded, but the spawned JVM exited with a non-zero
     *  status due to an uncaught exception in the evaluated program. */
    RUNTIME,

    /** The watchdog thread forcibly terminated the process because it
     *  exceeded the configured wall-clock execution deadline. */
    TIMEOUT
}