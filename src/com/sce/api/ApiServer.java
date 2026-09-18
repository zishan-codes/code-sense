package com.sce.api;

import com.sce.SystemController;
import com.sce.history.EvaluationHistoryService;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;

/**
 * ApiServer
 *
 * Owns the lifecycle of the REST API's HTTP server: creation, context
 * registration, start, and clean stop.
 *
 * The server only wires HTTP paths to handlers and manages the underlying
 * HttpServer instance. Evaluation/business logic remains inside the shared
 * SystemController. History/analytics logic remains inside
 * EvaluationHistoryService, which this class constructs exactly once and
 * shares with both HistoryHandler and AnalyticsHandler — no second
 * EvaluationHistoryService, no duplicate repository, no direct database
 * access is introduced here.
 *
 * The same SystemController instance supplied by the application is shared
 * between the REST API and any other caller, including the Swing UI.
 *
 * CORS: every context below is registered wrapped in CorsHandler, which
 * adds the headers a browser requires to call this API from a different
 * local origin (e.g. a frontend served on a different port), and answers
 * OPTIONS preflight requests directly without ever invoking the wrapped
 * handler. This is the single, centralized place CORS is implemented —
 * HealthHandler, EvaluateHandler, HistoryHandler, and AnalyticsHandler
 * are completely unmodified and have no knowledge that CORS exists.
 *
 * @author Smart Code Evaluator Team
 */
public class ApiServer {

    /** Base path reserved for all REST API endpoints. */
    public static final String API_BASE_PATH = "/api";

    /** Day 5.1 health-check endpoint. */
    private static final String HEALTH_PATH = API_BASE_PATH + "/health";

    /** Day 5.2 evaluation endpoint. */
    private static final String EVALUATE_PATH = API_BASE_PATH + "/evaluate";

    /** Day 5.3 history endpoint (also matches /api/history/{id}). */
    private static final String HISTORY_PATH = API_BASE_PATH + "/history";

    /** Day 5.4 analytics endpoint. */
    private static final String ANALYTICS_PATH = API_BASE_PATH + "/analytics";

    /** Backlog passed to HttpServer.create(); 0 requests system default. */
    private static final int DEFAULT_BACKLOG = 0;

    /** Stop immediately; no graceful-drain period at this stage. */
    private static final int STOP_DELAY_SECONDS = 0;

    private final ApiConfig config;
    private final SystemController systemController;

    /**
     * The single shared EvaluationHistoryService instance for this
     * server's lifetime, used by both HistoryHandler and AnalyticsHandler.
     * Constructed once, here, using EvaluationHistoryService's own no-arg
     * constructor (which builds its own internal EvaluationRepository) —
     * ApiServer does not construct or hold an EvaluationRepository itself,
     * preserving the existing dependency direction (API -> service ->
     * repository).
     */
    private final EvaluationHistoryService evaluationHistoryService;

    private HttpServer httpServer;
    private volatile boolean running;

    /**
     * Constructs an ApiServer using the default configuration and the
     * supplied shared SystemController.
     *
     * @param systemController the shared controller used by the API
     */
    public ApiServer(SystemController systemController) {
        this(new ApiConfig(), systemController);
    }

    /**
     * Constructs an ApiServer using the supplied configuration and the
     * supplied shared SystemController.
     *
     * @param config the API configuration; must not be null
     * @param systemController the shared evaluation controller; must not be null
     */
    public ApiServer(ApiConfig config, SystemController systemController) {
        if (config == null) {
            throw new IllegalArgumentException(
                    "ApiServer: config must not be null."
            );
        }

        if (systemController == null) {
            throw new IllegalArgumentException(
                    "ApiServer: systemController must not be null."
            );
        }

        this.config = config;
        this.systemController = systemController;
        this.evaluationHistoryService = new EvaluationHistoryService();
    }

    /**
     * Creates the underlying HttpServer, registers API contexts,
     * and starts listening for requests.
     *
     * @throws IOException if the server cannot bind to the configured port
     */
    public void start() throws IOException {
        if (running) {
            System.out.println(
                    "[ApiServer] start() called but server is already running — ignoring."
            );
            return;
        }

        httpServer = HttpServer.create(
                new InetSocketAddress(config.getPort()),
                DEFAULT_BACKLOG
        );

        httpServer.createContext(
                HEALTH_PATH,
                new CorsHandler(new HealthHandler())
        );

        httpServer.createContext(
                EVALUATE_PATH,
                new CorsHandler(new EvaluateHandler(systemController))
        );

        httpServer.createContext(
                HISTORY_PATH,
                new CorsHandler(new HistoryHandler(evaluationHistoryService))
        );

        httpServer.createContext(
                ANALYTICS_PATH,
                new CorsHandler(new AnalyticsHandler(evaluationHistoryService))
        );

        httpServer.setExecutor(null);
        httpServer.start();
        running = true;

        System.out.println(
                "[ApiServer] Started, listening on port " + getPort() + "."
        );
    }

    /**
     * Stops the HTTP server immediately.
     */
    public void stop() {
        if (httpServer != null) {
            httpServer.stop(STOP_DELAY_SECONDS);
            httpServer = null;
        }

        running = false;

        System.out.println("[ApiServer] Stopped.");
    }

    /**
     * @return true if the server is currently running.
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * @return the actual TCP port currently bound by the server.
     * @throws IllegalStateException if the server has not been started.
     */
    public int getPort() {
        if (httpServer == null) {
            throw new IllegalStateException(
                    "ApiServer.getPort: server has not been started (or has been stopped)."
            );
        }

        return httpServer.getAddress().getPort();
    }
}