package com.sce;

import com.sce.ai.AIConnector;
import com.sce.ai.AIResponse;
import com.sce.core.EvaluationRequest;
import com.sce.engine.CompileResult;
import com.sce.engine.EvaluationException;
import com.sce.engine.ExecResult;
import com.sce.engine.ExecutionEngine;
import com.sce.engine.Stage;
import com.sce.io.FileHandler;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import com.sce.ui.EvaluationCallback;
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
 * This is what guarantees the future Swing UI's event-dispatch thread
 * (or a CLI's main thread) is never blocked by a slow compilation, a
 * long-running child process, or a slow AI API call — it only ever
 * blocks briefly on queue.put(), which is effectively instantaneous for
 * an unbounded LinkedBlockingQueue.
 *
 * THREADING MODEL: exactly one worker thread processes the queue, so
 * evaluations are handled strictly one at a time, in submission order.
 * This is a deliberate simplification for the current sprint — it keeps
 * concurrent-evaluation complexity (and the risk of exhausting the
 * ExecutionEngine's own bounded thread pool) out of this class entirely.
 * Scaling to multiple concurrent evaluations, if ever required, should
 * be done by running multiple worker threads against this same queue,
 * not by changing handleSubmission()'s contract.
 *
 * @author Smart Code Evaluator Team
 */
public class SystemController {

    private final ExecutionEngine executionEngine;
    private final FileHandler fileHandler;
    private final AIConnector aiConnector;
    private final BlockingQueue<EvaluationRequest> requestQueue;

    /**
     * Guards the worker loop's main condition. Declared volatile because
     * it is written by shutdown() (called from whatever thread owns the
     * controller, e.g. the UI thread) and read continuously by the
     * separate worker thread — without volatile, the worker thread could
     * legally cache a stale 'true' value and never observe the shutdown
     * request.
     */
    private volatile boolean isRunning;

    /** Optional listener for evaluation lifecycle events, set by a UI
 *  layer (e.g. SmartEvaluatorUI). May be null if no UI is attached
 *  (e.g. when driven purely from a CLI test harness), in which case
 *  notifyCallback() below is a safe no-op. */
    private volatile EvaluationCallback callback;

    /**
     * Reference to the background worker thread, retained so shutdown()
     * can interrupt it directly (e.g., to break it out of a blocking
     * queue.take() call it may currently be parked in).
     */
    private Thread workerThread;

    /**
     * Initializes all composed components and the request queue. Does
     * NOT start the worker thread — call start() separately once the
     * caller (UI or test harness) is ready to begin processing.
     */
    public SystemController() {
        this.executionEngine = new ExecutionEngine();
        this.fileHandler = new FileHandler();
        this.aiConnector = new AIConnector();
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
            requestQueue.put(request);
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
                EvaluationRequest request = requestQueue.take();
                processEvaluation(request);
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
     * Executes the full evaluation pipeline for a single request:
     * workspace creation, source-file writing, compilation, execution,
     * and — on any failure stage — an AI debugging hint lookup. Runs
     * entirely on the background worker thread; never called from
     * handleSubmission() directly.
     *
     * Exception handling policy: EvaluationException (engine-level
     * failures, e.g. javac/java could not be launched) and IOException
     * (workspace/file failures) are both caught here and logged, but
     * NEVER allowed to propagate out of this method. Since this method
     * runs on the single, long-lived worker thread, an uncaught
     * exception here would kill that thread and silently stop all future
     * evaluations from ever being processed — which would be a far worse
     * failure than losing the result of one bad request.
     *
     * @param request the evaluation request to process
     */
    private void processEvaluation(EvaluationRequest request) {
        Path workspace = null;

        try {
            // ---- Workspace + source setup ----
            workspace = fileHandler.createWorkspace();
            Path sourceFile = fileHandler.writeSource(workspace, request);

            notifyCallback(() -> callback.onStatusUpdate("Compiling " + request.getClassName() + "..."));

            // ---- Compile ----
            CompileResult compileResult = executionEngine.compile(sourceFile);

            if (!compileResult.isSuccessful()) {
                System.out.println("[SystemController] Compilation FAILED for class '"
                        + request.getClassName() + "'.");
                System.out.println("[SystemController] Compiler output:\n" + compileResult.getOutput());

                notifyCallback(() -> callback.onConsoleOutput(compileResult.getOutput(), true));
                notifyCallback(() -> callback.onStatusUpdate("Fetching AI debugging hint..."));

                AIResponse hint = aiConnector.fetchDebuggingHint(
                        request.getSourceCode(), compileResult.getOutput(), Stage.COMPILE
                );
                printAIResponse(hint);
                notifyCallback(() -> callback.onAIHintReceived(hint.getHint(), hint.getSuggestedFix()));
                return; 
            }

            System.out.println("[SystemController] Compilation succeeded for class '"
                    + request.getClassName() + "'. Proceeding to execution...");
            
            notifyCallback(() -> callback.onConsoleOutput("Compilation succeeded. Executing...\n", false));
            notifyCallback(() -> callback.onStatusUpdate("Executing..."));

            // ---- Execute ----
            ExecResult execResult = executionEngine.execute(workspace, request.getClassName());

            if (execResult.isTimeout()) {
                System.out.println("[SystemController] Execution TIMED OUT for class '"
                        + request.getClassName() + "'.");
                System.out.println("[SystemController] Captured output before termination:\n"
                        + execResult.getStdout());

                notifyCallback(() -> callback.onConsoleOutput(execResult.getStderr(), true));
                notifyCallback(() -> callback.onStatusUpdate("Fetching AI debugging hint..."));

                AIResponse hint = aiConnector.fetchDebuggingHint(
                        request.getSourceCode(), execResult.getStderr(), Stage.TIMEOUT
                );
                printAIResponse(hint);
                notifyCallback(() -> callback.onAIHintReceived(hint.getHint(), hint.getSuggestedFix()));

            } else if (execResult.getExitCode() != 0) {
                System.out.println("[SystemController] Runtime error for class '"
                        + request.getClassName() + "' (exit code " + execResult.getExitCode() + ").");
                System.out.println("[SystemController] Captured stderr:\n" + execResult.getStderr());

                notifyCallback(() -> callback.onConsoleOutput(execResult.getStderr(), true));
                notifyCallback(() -> callback.onStatusUpdate("Fetching AI debugging hint..."));

                AIResponse hint = aiConnector.fetchDebuggingHint(
                        request.getSourceCode(), execResult.getStderr(), Stage.RUNTIME
                );
                printAIResponse(hint);
                notifyCallback(() -> callback.onAIHintReceived(hint.getHint(), hint.getSuggestedFix()));

            } else {
                System.out.println("[SystemController] Execution SUCCEEDED for class '"
                        + request.getClassName() + "'.");
                System.out.println("[SystemController] Program output:\n" + execResult.getStdout());

                notifyCallback(() -> callback.onConsoleOutput(execResult.getStdout(), false));
            }

        } catch (EvaluationException e) {
            System.err.println("[SystemController] EvaluationException at stage " + e.getStage()
                    + " for class '" + request.getClassName() + "': " + e.getMessage());
            if (e.getCause() != null) {
                System.err.println("[SystemController] Underlying cause: " + e.getCause());
            }
            notifyCallback(() -> callback.onConsoleOutput("Engine error at stage " + e.getStage() + ": " + e.getMessage(), true));

        } catch (IOException e) {
            System.err.println("[SystemController] I/O error while processing class '"
                    + request.getClassName() + "': " + e.getMessage());
            notifyCallback(() -> callback.onConsoleOutput("Environment error: " + e.getMessage(), true));

        } finally {
            if (workspace != null) {
                fileHandler.cleanup(workspace);
            }
            notifyCallback(() -> callback.onEvaluationComplete());
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
