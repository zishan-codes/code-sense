package com.sce.engine;

import com.sce.io.FileHandler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ExecutionEngine
 *
 * The Concurrency Engine's core process manager. Responsible for
 * spawning javac/java as external OS processes via ProcessBuilder,
 * draining their output streams safely, and enforcing a hard wall-clock
 * execution deadline via a watchdog thread.
 *
 * THREAD SAFETY: this class holds no per-evaluation mutable state as
 * instance fields — every piece of evaluation-specific state (the
 * Process handle, the timedOut flag, the gobbler Futures) is local to
 * the compile()/execute() call stack. This means a single ExecutionEngine
 * instance can be safely shared and invoked concurrently, from multiple
 * threads, by the AI UI Controller — concurrent calls are isolated from
 * one another and are only rate-limited by the shared bounded thread pool.
 *
 * DEADLOCK PREVENTION (see project SRS, Section 4.2, for full rationale):
 *  - A bounded ExecutorService caps the number of concurrent native
 *    processes and gobbler threads, preventing resource exhaustion
 *    under load (hold-and-wait mitigation).
 *  - stdout and stderr are ALWAYS drained on two independent threads,
 *    never sequentially on one, which is the standard fix for the
 *    ProcessBuilder pipe-buffer deadlock (a child process blocks
 *    writing once its OS pipe buffer fills, if nobody is reading it).
 *  - The watchdog thread is a daemon thread, so an abandoned or
 *    never-completing evaluation can never prevent JVM shutdown.
 *
 * @author Smart Code Evaluator Team
 */
public class ExecutionEngine {

    /** Maximum number of concurrent native processes / gobbler tasks. */
    private static final int POOL_SIZE = 4;

    /** Hard wall-clock deadline for a single execution, in milliseconds. */
    private static final long EXECUTION_TIMEOUT_MS = 3000L;

    /** Grace period allowed for gobbler threads to finish flushing captured
     *  output after the underlying process has already exited or been killed. */
    private static final long STREAM_DRAIN_GRACE_SECONDS = 5L;

    private final ExecutorService pool;
    private final FileHandler fileHandler;

    /**
     * Constructs an ExecutionEngine with a bounded, daemon-backed thread
     * pool. Pool threads are marked as daemon threads as a defensive
     * measure (belt-and-suspenders alongside the explicitly-required
     * daemon watchdog thread in execute()) so that even if shutdown()
     * is never called, an idle pool cannot by itself keep the JVM alive.
     */
    public ExecutionEngine() {
        AtomicInteger threadCounter = new AtomicInteger(1);
        ThreadFactory daemonFactory = runnable -> {
            Thread t = new Thread(runnable, "sce-engine-worker-" + threadCounter.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
        this.pool = Executors.newFixedThreadPool(POOL_SIZE, daemonFactory);
        this.fileHandler = new FileHandler();
    }

    /**
     * Compiles the given Java source file by invoking javac as an external
     * process. Compiler diagnostics (stdout and stderr combined) are
     * captured and returned regardless of whether compilation succeeded —
     * a non-zero javac exit code is normal, expected behavior here and is
     * represented as CompileResult(false, ...), NOT as a thrown exception.
     *
     * @param sourceFile the .java file to compile; must exist and its
     *                    filename must match its public class name
     * @return a CompileResult describing success/failure and diagnostic output
     * @throws EvaluationException if javac itself could not be launched,
     *                              or its output streams could not be drained
     *                              (i.e., an engine-level failure, not a
     *                              student-code compilation failure)
     * @throws IllegalArgumentException if sourceFile is null or does not exist
     */
    public CompileResult compile(Path sourceFile) {
        if (sourceFile == null || !Files.isRegularFile(sourceFile)) {
            throw new IllegalArgumentException(
                "compile: sourceFile must be an existing, regular .java file."
            );
        }

        Path workspace = sourceFile.getParent();
        ProcessBuilder builder = new ProcessBuilder("javac", sourceFile.getFileName().toString());
        builder.directory(workspace.toFile());

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new EvaluationException(
                Stage.COMPILE,
                "Failed to start javac process for '" + sourceFile + "'. " +
                "Verify that the JDK's javac binary is available on PATH.",
                e
            );
        }

        final Process compileProcess = process;
        Future<String> stdoutFuture = pool.submit(() -> fileHandler.readStream(compileProcess.getInputStream()));
        Future<String> stderrFuture = pool.submit(() -> fileHandler.readStream(compileProcess.getErrorStream()));

        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new EvaluationException(
                Stage.COMPILE,
                "Compilation was interrupted while waiting for javac to exit.",
                e
            );
        }

        String stdout;
        String stderr;
        try {
            stdout = stdoutFuture.get(STREAM_DRAIN_GRACE_SECONDS, TimeUnit.SECONDS);
            stderr = stderrFuture.get(STREAM_DRAIN_GRACE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EvaluationException(Stage.COMPILE, "Interrupted while draining javac output.", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new EvaluationException(Stage.COMPILE, "Failed to drain javac output streams.", e);
        }

        String combinedOutput = stdout.isEmpty() ? stderr
                : (stderr.isEmpty() ? stdout : stdout + "\n" + stderr);

        return new CompileResult(exitCode == 0, combinedOutput);
    }

    /**
     * Executes the compiled class named by className, using workspace as
     * both the working directory and the classpath. Enforces a hard
     * wall-clock timeout of {@link #EXECUTION_TIMEOUT_MS} via a dedicated
     * daemon watchdog thread — if the process has not completed by the
     * deadline (e.g., the student's program contains an infinite loop),
     * it is forcibly terminated and the returned ExecResult carries the
     * reserved -1 timeout exit code.
     *
     * @param workspace  the directory containing the compiled .class files;
     *                    used as both the process working directory and classpath
     * @param className   the fully-qualified name of the class containing main()
     * @return an ExecResult describing the exit code, stdout, and stderr —
     *          exitCode == -1 indicates a watchdog-enforced timeout
     * @throws EvaluationException if the java process could not be launched,
     *                              its streams could not be drained, or the
     *                              execution thread was interrupted
     * @throws IllegalArgumentException if workspace or className is invalid
     */
    public ExecResult execute(Path workspace, String className) {
        if (workspace == null || !Files.isDirectory(workspace)) {
            throw new IllegalArgumentException("execute: workspace must be an existing directory.");
        }
        if (className == null || className.trim().isEmpty()) {
            throw new IllegalArgumentException("execute: className must not be null or blank.");
        }

        ProcessBuilder builder = new ProcessBuilder("java", "-cp", workspace.toString(), className);
        builder.directory(workspace.toFile());

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new EvaluationException(
                Stage.RUNTIME,
                "Failed to start JVM process for class '" + className + "'. " +
                "Verify that the 'java' binary is available on PATH.",
                e
            );
        }

        final Process execProcess = process;
        final AtomicBoolean timedOut = new AtomicBoolean(false);

        // --- Watchdog / timer thread -----------------------------------
        // Races against the process: sleeps for the configured deadline,
        // then forcibly kills the process if it is still alive. Marked as
        // a daemon thread per project convention so an abandoned or
        // never-terminating evaluation can never block JVM shutdown.
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(EXECUTION_TIMEOUT_MS);
                if (execProcess.isAlive()) {
                    timedOut.set(true);
                    execProcess.destroyForcibly();
                }
            } catch (InterruptedException e) {
                // Interrupted deliberately by the main execution thread
                // below once the process has already finished naturally —
                // this is the expected, non-error path, so we simply let
                // the watchdog thread exit without killing anything.
                Thread.currentThread().interrupt();
            }
        }, "sce-watchdog-" + className);
        watchdog.setDaemon(true);
        watchdog.start();

        // --- Stream gobbling ---------------------------------------------
        // stdout and stderr MUST be drained on two independent threads to
        // avoid the ProcessBuilder pipe-buffer deadlock: if only one thread
        // read both streams sequentially, a process that fills the stderr
        // pipe buffer while this thread is still blocked reading stdout
        // would stall forever.
        Future<String> stdoutFuture = pool.submit(() -> fileHandler.readStream(execProcess.getInputStream()));
        Future<String> stderrFuture = pool.submit(() -> fileHandler.readStream(execProcess.getErrorStream()));

        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            watchdog.interrupt();
            throw new EvaluationException(
                Stage.RUNTIME,
                "Execution was interrupted while waiting for the JVM process to exit.",
                e
            );
        } finally {
            // The process has exited (naturally or via destroyForcibly()).
            // Interrupt the watchdog so it does not needlessly sleep out
            // its full timeout window before exiting on its own.
            watchdog.interrupt();
        }

        String stdout;
        String stderr;
        try {
            stdout = stdoutFuture.get(STREAM_DRAIN_GRACE_SECONDS, TimeUnit.SECONDS);
            stderr = stderrFuture.get(STREAM_DRAIN_GRACE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EvaluationException(Stage.RUNTIME, "Interrupted while draining process output.", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new EvaluationException(Stage.RUNTIME, "Failed to drain process output streams.", e);
        }

        if (timedOut.get()) {
            String timeoutNote = "Execution exceeded the configured timeout of "
                    + EXECUTION_TIMEOUT_MS + "ms and was forcibly terminated.";
            String stderrWithNote = stderr.isEmpty() ? timeoutNote : stderr + "\n" + timeoutNote;
            return new ExecResult(ExecResult.TIMEOUT_EXIT_CODE, stdout, stderrWithNote);
        }

        return new ExecResult(exitCode, stdout, stderr);
    }

    /**
     * Gracefully shuts down the internal thread pool. Should be called
     * once during application shutdown (e.g., from the AI UI Controller's
     * window-closing handler). Not calling this is not catastrophic —
     * pool threads are daemon threads — but graceful shutdown avoids
     * abruptly cancelling any in-flight gobbler task.
     */
    public void shutdown() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
    }
}