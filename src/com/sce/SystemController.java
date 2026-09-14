package com.sce;

import com.sce.ai.AIConnector;
import com.sce.ai.AIResponse;
import com.sce.core.EvaluationRecord;
import com.sce.core.EvaluationRecord.AiRequestStatus;
import com.sce.core.EvaluationRequest;
import com.sce.core.Result;
import com.sce.database.EvaluationRepository;
import com.sce.engine.CompileResult;
import com.sce.engine.EvaluationException;
import com.sce.engine.ExecResult;
import com.sce.engine.ExecutionEngine;
import com.sce.engine.Stage;
import com.sce.io.FileHandler;
import com.sce.ui.EvaluationCallback;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.swing.SwingUtilities;

/**
 * SystemController
 *
 * Central orchestrator tying the I/O Manager (FileHandler), Concurrency
 * Engine (ExecutionEngine), and AI Connector (AIConnector) together into
 * a single evaluation pipeline, per SRS Section 6.2.
 *
 * DESIGN: incoming evaluation requests are never processed on the calling
 * thread. handleSubmission() only enqueues; a single dedicated background
 * worker thread, started by start(), drains the queue and runs the full
 * compile -> execute -> (conditionally) AI-hint pipeline sequentially.
 * This is what guarantees the Swing UI's event-dispatch thread (or a
 * CLI's main thread) is never blocked by a slow compilation, a
 * long-running child process, or a slow AI API call — it only ever
 * blocks briefly on queue.put(), which is effectively instantaneous for
 * an unbounded LinkedBlockingQueue.
 *
 * THREADING MODEL: exactly one worker thread processes the queue, so
 * evaluations are handled strictly one at a time, in submission order.
 * This is a deliberate simplification — it keeps concurrent-evaluation
 * complexity (and the risk of exhausting the ExecutionEngine's own
 * bounded thread pool) out of this class entirely. Scaling to multiple
 * concurrent evaluations, if ever required, should be done by running
 * multiple worker threads against this same queue, not by changing
 * handleSubmission()'s contract.
 *
 * EVALUATION RECORD INTEGRATION (Day 1): for every evaluation that
 * reaches a terminal, classifiable outcome (SUCCESS, COMPILE_ERROR,
 * RUNTIME_ERROR, TIMEOUT), an immutable EvaluationRecord is assembled
 * from the exact same CompileResult/ExecResult/AIResponse values this
 * class already computes, and is retained via getLastEvaluationRecord().
 *
 * PERSISTENCE INTEGRATION (Day 2): immediately after each of the four
 * EvaluationRecord assignments above, the record is now also persisted
 * via EvaluationRepository.save(). This call runs synchronously on the
 * same worker thread — no new thread, queue, or pool is introduced. A
 * single-row SQLite insert is cheaper than the Process.waitFor() and AI
 * HTTP calls that already run unconditionally on this thread, so this
 * does not change the thread's fundamental blocking profile. A
 * persistence failure (SQLException) is caught, logged, and swallowed —
 * exactly like EvaluationException and IOException are already handled
 * elsewhere in this class — so a broken database can never crash the
 * worker thread or prevent the evaluation's result/AI hint from having
 * already reached the UI via the existing, unaffected callback path.
 *
 * CORRELATION-SAFE SYNCHRONOUS SUBMISSION (Day 5.2): in addition to the
 * existing fire-and-forget handleSubmission(), this class now also
 * supports submitAndAwaitResult(), which returns the EvaluationRecord
 * belonging to that exact submission — not a read of shared state, but
 * a genuine per-call return value. This is achieved by changing the
 * queue's element type from EvaluationRequest to an internal
 * PendingEvaluation record pairing the request with an optional
 * CompletableFuture<EvaluationRecord>. The queue itself, the single
 * worker thread, and strict FIFO processing order are completely
 * unchanged — both submission paths are drained by the SAME worker
 * thread in the SAME order they were enqueued; only the payload carried
 * through the queue changed shape. handleSubmission() continues to
 * enqueue a PendingEvaluation with a null future, preserving its exact
 * existing fire-and-forget behavior (including its effect on
 * lastEvaluationRecord and persistence) with no observable change.
 *
 * @author Smart Code Evaluator Team
 */
public class SystemController {

    private final ExecutionEngine executionEngine;
    private final FileHandler fileHandler;
    private final AIConnector aiConnector;
    private final EvaluationRepository evaluationRepository;
    private final BlockingQueue<PendingEvaluation> requestQueue;

    /**
     * Guards the worker loop's main condition. Declared volatile because
     * it is written by shutdown() (called from whatever thread owns the
     * controller, e.g. the UI thread) and read continuously by the
     * separate worker thread — without volatile, the worker thread could
     * legally cache a stale 'true' value and never observe the shutdown
     * request.
     */
    private volatile boolean isRunning;

    /**
     * Reference to the background worker thread, retained so shutdown()
     * can interrupt it directly (e.g., to break it out of a blocking
     * queue.take() call it may currently be parked in).
     */
    private Thread workerThread;

    /**
     * Optional listener for evaluation lifecycle events, set by a UI
     * layer (e.g. SmartEvaluatorUI). May be null if no UI is attached
     * (e.g. when driven purely from a CLI test harness), in which case
     * notifyCallback() below is a safe no-op.
     */
    private volatile EvaluationCallback callback;

    /**
     * Retains the most recently completed EvaluationRecord, written only
     * by the single worker thread and marked volatile so any other
     * thread can safely observe the latest value without additional
     * synchronization.
     */
    private volatile EvaluationRecord lastEvaluationRecord;

    /**
     * PendingEvaluation
     *
     * Internal queue payload pairing an EvaluationRequest with an
     * optional per-submission result future. Never exposed outside this
     * class — callers interact only with handleSubmission() or
     * submitAndAwaitResult(), never with this type directly.
     *
     * resultFuture == null   -> the legacy fire-and-forget path
     *                           (handleSubmission()): the worker thread
     *                           updates lastEvaluationRecord and persists
     *                           the record exactly as before, and does
     *                           NOT attempt to complete any future.
     * resultFuture != null   -> the correlation-safe synchronous path
     *                           (submitAndAwaitResult()): the worker
     *                           thread completes this specific future
     *                           with the resulting EvaluationRecord (or
     *                           completes it exceptionally on an
     *                           engine-level failure), in addition to
     *                           the existing lastEvaluationRecord/
     *                           persistence behavior, which is
     *                           unconditional for both paths.
     */
    private record PendingEvaluation(EvaluationRequest request, CompletableFuture<EvaluationRecord> resultFuture) {
    }

    /**
     * Initializes all composed components and the request queue. Does
     * NOT start the worker thread — call start() separately once the
     * caller (UI or test harness) is ready to begin processing.
     */
    public SystemController() {
        this.executionEngine = new ExecutionEngine();
        this.fileHandler = new FileHandler();
        this.aiConnector = new AIConnector();
        this.evaluationRepository = new EvaluationRepository();
        this.requestQueue = new LinkedBlockingQueue<>();
        this.isRunning = false;
    }

    /**
     * Starts the background worker thread that continuously drains
     * requestQueue and processes each EvaluationRequest through the full
     * pipeline. Safe to call only once per controller instance; calling
     * it again while already running has no effect beyond a logged
     * warning, since a second worker thread would break the
     * one-request-at-a-time processing guarantee this class relies on.
     *
     * The worker thread is marked as a daemon thread so an application
     * that forgets to call shutdown() explicitly can still exit cleanly —
     * consistent with the daemon-thread convention already established
     * in ExecutionEngine's watchdog and pool threads.
     */
    public void start() {
        if (isRunning) {
            System.out.println("[SystemController] start() called but controller is already running — ignoring.");
            return;
        }

        isRunning = true;
        workerThread = new Thread(this::runWorkerLoop, "sce-controller-worker");
        workerThread.setDaemon(true);
        workerThread.start();

        System.out.println("[SystemController] Worker thread started. Ready to accept evaluation requests.");
    }

    /**
     * Registers a listener to receive evaluation lifecycle events. Safe to
     * call before or after start(); has no effect on already-completed
     * evaluations.
     *
     * @param callback the listener to notify, or null to detach any
     *                   currently registered listener
     */
    public void setCallback(EvaluationCallback callback) {
        this.callback = callback;
    }

    /**
     * Returns the most recently completed EvaluationRecord, or null if no
     * classifiable evaluation has completed yet since this controller was
     * constructed.
     *
     * @return the last completed EvaluationRecord, or null if none exists yet
     */
    public EvaluationRecord getLastEvaluationRecord() {
        return lastEvaluationRecord;
    }

    /**
     * Enqueues an evaluation request for asynchronous processing by the
     * background worker thread. Returns immediately — this method never
     * blocks the caller on compilation, execution, or AI network calls,
     * which is the entire point of routing submissions through a queue
     * rather than processing them inline.
     *
     * @param request the evaluation request to process; must not be null
     * @throws IllegalArgumentException if request is null
     * @throws IllegalStateException     if the controller has not been
     *                                     started (or has already been
     *                                     shut down), since a request
     *                                     enqueued with no worker running
     *                                     to consume it would sit
     *                                     silently forever
     */
    public void handleSubmission(EvaluationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("handleSubmission: request must not be null.");
        }
        if (!isRunning) {
            throw new IllegalStateException(
                "handleSubmission: SystemController is not running. Call start() before submitting requests."
            );
        }

        try {
            // put() is used over offer() for a queue that is logically
            // unbounded (LinkedBlockingQueue's default capacity is
            // Integer.MAX_VALUE), so this call will not actually block
            // in practice, but put() correctly documents the intent that
            // the caller is willing to wait if capacity were ever bounded.
            requestQueue.put(new PendingEvaluation(request, null));
        } catch (InterruptedException e) {
            // Restore the interrupt status for the calling thread rather
            // than swallowing it — this thread is very likely the UI
            // thread, and silently eating an interrupt here could mask a
            // shutdown signal intended for the caller's own thread.
            Thread.currentThread().interrupt();
            System.err.println("[SystemController] Interrupted while enqueuing request for class '"
                    + request.getClassName() + "'. Request was NOT submitted.");
        }
    }

    /**
     * Enqueues an evaluation request for processing by the SAME
     * background worker thread and SAME queue as handleSubmission(), and
     * blocks the CALLING thread until that specific request's
     * EvaluationRecord is available, or until the given timeout elapses.
     *
     * Unlike handleSubmission(), the result returned here is never read
     * from shared state (e.g. lastEvaluationRecord) — it is delivered
     * via a per-call CompletableFuture that only the worker thread
     * completes and only this call awaits, so concurrent callers
     * (including simultaneous Swing-originated submissions via
     * handleSubmission()) cannot observe or receive each other's
     * results. Because both submission paths share the same single
     * worker thread and queue, evaluations are still processed strictly
     * one at a time, in submission order, exactly as before.
     *
     * @param request the evaluation request to process; must not be null
     * @param timeout  the maximum time to wait for the evaluation to
     *                  complete; must be positive
     * @param unit     the time unit of the timeout argument; must not be null
     * @return the EvaluationRecord produced by this specific submission
     * @throws IllegalArgumentException if request or unit is null, or if
     *                                    timeout is not positive
     * @throws IllegalStateException     if the controller has not been
     *                                     started (or has already been
     *                                     shut down)
     * @throws InterruptedException      if the calling thread is
     *                                     interrupted while enqueuing the
     *                                     request or while waiting for
     *                                     the result
     * @throws TimeoutException          if the evaluation does not
     *                                     complete within the given
     *                                     timeout
     * @throws IOException               if the evaluation failed at the
     *                                     engine level due to a
     *                                     workspace/file I/O failure
     *                                     (the same failure class
     *                                     already caught internally by
     *                                     processEvaluation() for the
     *                                     handleSubmission() path)
     * @throws EvaluationException       if the evaluation failed at the
     *                                     engine level for a reason
     *                                     other than I/O (e.g. javac/java
     *                                     could not be launched) — this
     *                                     is unchecked, matching
     *                                     EvaluationException's own
     *                                     declared type
     */
    public EvaluationRecord submitAndAwaitResult(EvaluationRequest request, long timeout, TimeUnit unit)
            throws InterruptedException, TimeoutException, IOException {

        if (request == null) {
            throw new IllegalArgumentException("submitAndAwaitResult: request must not be null.");
        }
        if (unit == null) {
            throw new IllegalArgumentException("submitAndAwaitResult: unit must not be null.");
        }
        if (timeout <= 0) {
            throw new IllegalArgumentException(
                "submitAndAwaitResult: timeout must be positive (was " + timeout + ")."
            );
        }
        if (!isRunning) {
            throw new IllegalStateException(
                "submitAndAwaitResult: SystemController is not running. Call start() before submitting requests."
            );
        }

        CompletableFuture<EvaluationRecord> resultFuture = new CompletableFuture<>();

        try {
            requestQueue.put(new PendingEvaluation(request, resultFuture));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }

        try {
            return resultFuture.get(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            // Not expected to occur — processEvaluation() only ever
            // completes a future exceptionally with an IOException or an
            // EvaluationException (a RuntimeException). Wrapped
            // defensively rather than silently discarding the cause.
            throw new RuntimeException("submitAndAwaitResult: unexpected failure cause.", cause);
        }
    }

    /**
     * The worker thread's main loop: continuously blocks on
     * requestQueue.take() and dispatches each request to
     * processEvaluation() in turn, until isRunning is set to false (by
     * shutdown()) and the thread is interrupted out of its current wait.
     *
     * Intentionally private — this is purely internal machinery run on
     * the dedicated worker thread, never invoked directly by any caller.
     */
    private void runWorkerLoop() {
        while (isRunning) {
            try {
                PendingEvaluation pending = requestQueue.take();
                processEvaluation(pending);
            } catch (InterruptedException e) {
                // Expected, normal signal on shutdown(): re-assert the
                // interrupt flag and let the while(isRunning) condition
                // (now false, per shutdown()'s ordering) end the loop
                // cleanly rather than treating this as an error.
                Thread.currentThread().interrupt();
            }
        }
        System.out.println("[SystemController] Worker thread exiting.");
    }

    /**
     * Executes the full evaluation pipeline for a single pending
     * evaluation: workspace creation, source-file writing, compilation,
     * execution, and — on any failure stage — an AI debugging hint
     * lookup. Runs entirely on the background worker thread; never
     * called from handleSubmission() or submitAndAwaitResult() directly.
     *
     * For every branch that reaches a classifiable terminal outcome
     * (compile failure, timeout, runtime failure, or success), an
     * EvaluationRecord is assembled via buildEvaluationRecord(), stored
     * in lastEvaluationRecord, and immediately persisted via
     * persistRecord() — unconditionally, for BOTH submission paths. If
     * this pending evaluation carries a non-null resultFuture (i.e. it
     * came from submitAndAwaitResult()), that future is also completed
     * with the same record immediately afterward.
     *
     * Exception handling policy: EvaluationException (engine-level
     * failures, e.g. javac/java could not be launched), IOException
     * (workspace/file failures), and now SQLException (persistence
     * failures, handled inside persistRecord()) are all caught and
     * logged, but NEVER allowed to propagate out of this method. Since
     * this method runs on the single, long-lived worker thread, an
     * uncaught exception here would kill that thread and silently stop
     * all future evaluations from ever being processed — which would be
     * a far worse failure than losing the result of one bad request or
     * one failed database write. If a non-null resultFuture is present,
     * it is completed exceptionally with the caught exception so
     * submitAndAwaitResult() can surface it to its caller; the
     * handleSubmission() path (null resultFuture) is unaffected and
     * continues to only log these failures, exactly as before.
     *
     * @param pending the request (and optional result future) to process
     */
    private void processEvaluation(PendingEvaluation pending) {
        EvaluationRequest request = pending.request();
        CompletableFuture<EvaluationRecord> resultFuture = pending.resultFuture();

        Path workspace = null;
        long startTimeMillis = System.currentTimeMillis();

        try {
            // ---- Workspace + source setup ----
            workspace = fileHandler.createWorkspace();
            Path sourceFile = fileHandler.writeSource(workspace, request);

            // ---- Compile ----
            CompileResult compileResult = executionEngine.compile(sourceFile);

            if (!compileResult.isSuccessful()) {
                System.out.println("[SystemController] Compilation FAILED for class '"
                        + request.getClassName() + "'.");
                System.out.println("[SystemController] Compiler output:\n" + compileResult.getOutput());

                AIResponse hint = aiConnector.fetchDebuggingHint(
                        request.getSourceCode(), compileResult.getOutput(), Stage.COMPILE
                );
                printAIResponse(hint);

                lastEvaluationRecord = buildEvaluationRecord(
                        request,
                        Result.Status.COMPILE_ERROR,
                        EvaluationRecord.COMPILE_FAILURE_EXIT_CODE,
                        "",
                        compileResult.getOutput(),
                        hint,
                        startTimeMillis
                );
                persistRecord(lastEvaluationRecord);
                if (resultFuture != null) {
                    resultFuture.complete(lastEvaluationRecord);
                }
                return; // No point attempting execution against a failed compile.
            }

            System.out.println("[SystemController] Compilation succeeded for class '"
                    + request.getClassName() + "'. Proceeding to execution...");

            // ---- Execute ----
            ExecResult execResult = executionEngine.execute(workspace, request.getClassName());

            if (execResult.isTimeout()) {
                System.out.println("[SystemController] Execution TIMED OUT for class '"
                        + request.getClassName() + "'.");
                System.out.println("[SystemController] Captured output before termination:\n"
                        + execResult.getStdout());

                AIResponse hint = aiConnector.fetchDebuggingHint(
                        request.getSourceCode(), execResult.getStderr(), Stage.TIMEOUT
                );
                printAIResponse(hint);

                lastEvaluationRecord = buildEvaluationRecord(
                        request,
                        Result.Status.TIMEOUT,
                        execResult.getExitCode(),
                        execResult.getStdout(),
                        execResult.getStderr(),
                        hint,
                        startTimeMillis
                );
                persistRecord(lastEvaluationRecord);
                if (resultFuture != null) {
                    resultFuture.complete(lastEvaluationRecord);
                }

            } else if (execResult.getExitCode() != 0) {
                System.out.println("[SystemController] Runtime error for class '"
                        + request.getClassName() + "' (exit code " + execResult.getExitCode() + ").");
                System.out.println("[SystemController] Captured stderr:\n" + execResult.getStderr());

                AIResponse hint = aiConnector.fetchDebuggingHint(
                        request.getSourceCode(), execResult.getStderr(), Stage.RUNTIME
                );
                printAIResponse(hint);

                lastEvaluationRecord = buildEvaluationRecord(
                        request,
                        Result.Status.RUNTIME_ERROR,
                        execResult.getExitCode(),
                        execResult.getStdout(),
                        execResult.getStderr(),
                        hint,
                        startTimeMillis
                );
                persistRecord(lastEvaluationRecord);
                if (resultFuture != null) {
                    resultFuture.complete(lastEvaluationRecord);
                }

            } else {
                System.out.println("[SystemController] Execution SUCCEEDED for class '"
                        + request.getClassName() + "'.");
                System.out.println("[SystemController] Program output:\n" + execResult.getStdout());

                // Success never triggers an AI call — matches existing
                // behavior exactly. Passing null here signals
                // buildEvaluationRecord() to record AiRequestStatus.NOT_REQUESTED.
                lastEvaluationRecord = buildEvaluationRecord(
                        request,
                        Result.Status.SUCCESS,
                        execResult.getExitCode(),
                        execResult.getStdout(),
                        execResult.getStderr(),
                        null,
                        startTimeMillis
                );
                persistRecord(lastEvaluationRecord);
                if (resultFuture != null) {
                    resultFuture.complete(lastEvaluationRecord);
                }
            }

        } catch (EvaluationException e) {
            // Engine-level failure — javac/java could not be launched, or
            // a process stream could not be drained. Distinct from a
            // normal student-code compile/runtime failure, which never
            // reaches this catch block. No EvaluationRecord is produced
            // or persisted here: this failure has no corresponding
            // Result.Status value.
            System.err.println("[SystemController] EvaluationException at stage " + e.getStage()
                    + " for class '" + request.getClassName() + "': " + e.getMessage());
            if (e.getCause() != null) {
                System.err.println("[SystemController] Underlying cause: " + e.getCause());
            }
            if (resultFuture != null) {
                resultFuture.completeExceptionally(e);
            }

        } catch (IOException e) {
            // Workspace creation or source-file write failure — an
            // "environment error" rather than a student-code error. Same
            // reasoning as above: no EvaluationRecord is produced.
            System.err.println("[SystemController] I/O error while processing class '"
                    + request.getClassName() + "': " + e.getMessage());
            if (resultFuture != null) {
                resultFuture.completeExceptionally(e);
            }

        } finally {
            // Runs unconditionally — success, any caught exception above,
            // or an early return from the COMPILE_ERROR branch — so a
            // temp workspace is never leaked regardless of how this
            // method exits.
            if (workspace != null) {
                fileHandler.cleanup(workspace);
            }
        }
    }

    /**
     * Assembles an immutable EvaluationRecord from the exact values this
     * evaluation's branch already computed — no new computation is
     * performed beyond generating a unique ID, capturing the completion
     * timestamp, and measuring elapsed duration since processEvaluation()
     * began.
     *
     * @param request          the original evaluation request
     * @param status            the terminal Result.Status classification
     *                           for this evaluation
     * @param exitCode          the OS-level exit code, or a reserved
     *                           sentinel (see EvaluationRecord's Javadoc)
     * @param stdout            captured standard output, or an empty string
     * @param stderr            captured standard error / compiler output,
     *                           or an empty string
     * @param aiResponse         the AIResponse returned by AIConnector, or
     *                           null if the AI was never called for this
     *                           evaluation (e.g. on success)
     * @param startTimeMillis    the wall-clock time, in milliseconds,
     *                           captured at the start of processEvaluation()
     * @return a fully populated, immutable EvaluationRecord
     */
    private EvaluationRecord buildEvaluationRecord(EvaluationRequest request,
                                                    Result.Status status,
                                                    int exitCode,
                                                    String stdout,
                                                    String stderr,
                                                    AIResponse aiResponse,
                                                    long startTimeMillis) {

        long durationMillis = System.currentTimeMillis() - startTimeMillis;

        String aiHint;
        String aiSuggestedFix;
        AiRequestStatus aiRequestStatus;

        if (aiResponse == null) {
            aiRequestStatus = AiRequestStatus.NOT_REQUESTED;
            aiHint = "";
            aiSuggestedFix = "";
        } else if (aiResponse.isSuccess()) {
            aiRequestStatus = AiRequestStatus.AVAILABLE;
            aiHint = aiResponse.getHint();
            aiSuggestedFix = aiResponse.getSuggestedFix();
        } else {
            aiRequestStatus = AiRequestStatus.UNAVAILABLE;
            // AIResponse.unavailable(...) already populates 'hint' with a
            // human-readable unavailability message and leaves
            // suggestedFix empty — reused as-is, no reformatting needed.
            aiHint = aiResponse.getHint();
            aiSuggestedFix = aiResponse.getSuggestedFix();
        }

        return new EvaluationRecord(
                UUID.randomUUID().toString(),
                Instant.now(),
                request.getClassName(),
                request.getSourceCode(),
                status,
                exitCode,
                stdout,
                stderr,
                aiHint,
                aiSuggestedFix,
                aiRequestStatus,
                durationMillis
        );
    }

    /**
     * Persists the given EvaluationRecord via EvaluationRepository,
     * running synchronously on the calling thread (the single worker
     * thread, in all current call sites). Any SQLException is caught,
     * logged, and swallowed — a persistence failure must never crash the
     * worker thread or affect any evaluation's already-delivered UI
     * result, exactly as EvaluationException and IOException are already
     * handled elsewhere in processEvaluation().
     *
     * @param record the EvaluationRecord to persist; must not be null
     */
    private void persistRecord(EvaluationRecord record) {
        try {
            evaluationRepository.save(record);
        } catch (SQLException e) {
            System.err.println("[SystemController] Failed to persist EvaluationRecord (id="
                    + record.getEvaluationId() + ") for class '" + record.getClassName()
                    + "': " + e.getMessage());
        }
    }

    /**
     * Uniformly prints an AIResponse to the console. Temporary
     * presentation logic standing in for the UI callback interface that
     * will replace it in a later sprint — kept as a single small method
     * so that replacement is a one-method change rather than a scattered
     * find-and-replace across processEvaluation().
     *
     * @param response the AIResponse returned by the AI Connector
     */
    private void printAIResponse(AIResponse response) {
        System.out.println("---------------- AI Debugging Hint ----------------");
        if (response.isSuccess()) {
            System.out.println("[HINT] " + response.getHint());
            if (!response.getSuggestedFix().isEmpty()) {
                System.out.println("[SUGGESTED FIX] " + response.getSuggestedFix());
            }
        } else {
            System.out.println("[AI UNAVAILABLE] " + response.getHint());
        }
        System.out.println("----------------------------------------------------");
    }

    /**
     * Dispatches a callback notification onto the Swing Event Dispatch
     * Thread, if a callback is currently registered. Centralizing the
     * invokeLater() wrapping here means processEvaluation() itself never
     * needs to know or care that it is running on a background worker
     * thread relative to the UI.
     *
     * @param callbackAction the callback invocation to run on the EDT
     */
    private void notifyCallback(Runnable callbackAction) {
        if (callback != null) {
            SwingUtilities.invokeLater(callbackAction);
        }
    }

    /**
     * Shuts down the controller: stops the worker loop, interrupts the
     * worker thread (in case it is currently blocked in
     * requestQueue.take()), and releases the ExecutionEngine's internal
     * thread pool. Safe to call even if start() was never called, or if
     * shutdown() is called more than once.
     *
     * Any requests still sitting in requestQueue at the time of shutdown
     * are deliberately left unprocessed and are not drained or logged
     * here — for a UI-driven tool, an in-flight request abandoned at
     * application close is expected behavior, not a data-loss bug worth
     * additional handling in this sprint.
     */
    public void shutdown() {
        isRunning = false;

        if (workerThread != null) {
            workerThread.interrupt();
        }

        executionEngine.shutdown();

        System.out.println("[SystemController] Shutdown complete.");
    }
}