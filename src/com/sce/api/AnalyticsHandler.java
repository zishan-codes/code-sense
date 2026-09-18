package com.sce.api;

import com.sce.analytics.EvaluationStatistics;
import com.sce.history.EvaluationHistoryException;
import com.sce.history.EvaluationHistoryService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

/**
 * AnalyticsHandler
 *
 * Handles:
 *   GET /api/analytics            (optionally ?limit=N)
 *
 * Delegates entirely to the existing EvaluationHistoryService.getStatistics(limit),
 * which itself retrieves recent records via EvaluationRepository and delegates
 * calculation to the stateless EvaluationAnalytics layer. This class performs
 * NO aggregation, NO SQL, and has NO dependency on EvaluationRepository,
 * SystemController, or ExecutionEngine — its only job is HTTP/JSON translation
 * around EvaluationHistoryService's existing, already-tested contract.
 *
 * VALIDATION CONVENTION: identical to Day 5.3's HistoryHandler — omitted limit
 * defaults to 10, non-positive/non-numeric/above-100 values are rejected with
 * HTTP 400 rather than silently clamped, consistent across every REST endpoint
 * in this project.
 *
 * RESPONSE FIELDS: every field serialized below is read directly from the
 * actual current EvaluationStatistics getters (getTotalEvaluations(),
 * getSuccessfulEvaluations(), getCompileErrorCount(), getRuntimeErrorCount(),
 * getTimeoutCount(), getSuccessRate(), getFailureRate(),
 * getAverageDurationMillis(), getAiRequestedCount(), getAiAvailableCount(),
 * getAiUnavailableCount()) — no field is invented, renamed, or omitted.
 *
 * @author Smart Code Evaluator Team
 */
final class AnalyticsHandler implements HttpHandler {

    private static final String METHOD_GET = "GET";

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 100;

    private final EvaluationHistoryService evaluationHistoryService;

    /**
     * Constructs an AnalyticsHandler backed by the given
     * EvaluationHistoryService. The same shared instance ApiServer already
     * constructs for HistoryHandler is reused here — no second service, no
     * second repository, no new database access path is introduced.
     *
     * @param evaluationHistoryService the service to delegate statistics
     *                                  calculation to; must not be null
     * @throws IllegalArgumentException if evaluationHistoryService is null
     */
    AnalyticsHandler(EvaluationHistoryService evaluationHistoryService) {
        if (evaluationHistoryService == null) {
            throw new IllegalArgumentException("AnalyticsHandler: evaluationHistoryService must not be null.");
        }
        this.evaluationHistoryService = evaluationHistoryService;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!METHOD_GET.equalsIgnoreCase(exchange.getRequestMethod())) {
                ApiResponse.sendJson(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }

            String query = exchange.getRequestURI().getQuery();
            String rawLimit = extractQueryParam(query, "limit");

            int limit;
            if (rawLimit == null) {
                limit = DEFAULT_LIMIT;
            } else {
                try {
                    limit = Integer.parseInt(rawLimit);
                } catch (NumberFormatException e) {
                    ApiResponse.sendJson(exchange, 400,
                            "{\"error\":\"Query parameter 'limit' must be a valid integer.\"}");
                    return;
                }

                if (limit <= 0) {
                    ApiResponse.sendJson(exchange, 400,
                            "{\"error\":\"Query parameter 'limit' must be positive.\"}");
                    return;
                }
                if (limit > MAX_LIMIT) {
                    ApiResponse.sendJson(exchange, 400,
                            "{\"error\":\"Query parameter 'limit' must not exceed " + MAX_LIMIT + ".\"}");
                    return;
                }
            }

            EvaluationStatistics statistics;
            try {
                statistics = evaluationHistoryService.getStatistics(limit);
            } catch (IllegalArgumentException e) {
                // Defensive: should not occur given the validation above,
                // but mapped correctly rather than falling through to the
                // generic 500 handler if the service's own validation rules
                // were ever to change.
                ApiResponse.sendJson(exchange, 400,
                        "{\"error\":\"" + JsonUtil.escapeJsonString(e.getMessage()) + "\"}");
                return;
            } catch (EvaluationHistoryException e) {
                ApiResponse.sendJson(exchange, 500,
                        "{\"error\":\"Analytics could not be retrieved due to an internal error.\"}");
                return;
            }

            ApiResponse.sendJson(exchange, 200, toJson(statistics));

        } catch (Exception unexpected) {
            // Final safety net: no exception message, class name, or
            // stack trace is ever sent to the client.
            try {
                ApiResponse.sendJson(exchange, 500, "{\"error\":\"Internal Server Error\"}");
            } catch (IOException ioException) {
                // Nothing further can be safely done if even the 500
                // response cannot be written.
            }
        } finally {
            exchange.close();
        }
    }

    /**
     * Serializes an EvaluationStatistics instance to a JSON object using
     * exactly its current fields/getters — no invented metrics, no
     * omissions. Numeric fields (int and double) are emitted as raw JSON
     * numbers, not strings, matching this project's existing convention
     * for exitCode/durationMillis in EvaluateHandler and HistoryHandler.
     *
     * Package-private (rather than private) specifically so
     * AnalyticsApiTest — in the same package — can verify this
     * serialization directly against EvaluationStatistics.empty() without
     * requiring a genuinely empty database, which the shared, already-
     * populated SQLite database used by other tests cannot provide.
     *
     * @param statistics the statistics snapshot to serialize; must not be null
     * @return a JSON object string representing every field of statistics
     */
    String toJson(EvaluationStatistics statistics) {
        StringBuilder json = new StringBuilder(256);
        json.append('{');
        json.append("\"totalEvaluations\":").append(statistics.getTotalEvaluations()).append(',');
        json.append("\"successfulEvaluations\":").append(statistics.getSuccessfulEvaluations()).append(',');
        json.append("\"compileErrorCount\":").append(statistics.getCompileErrorCount()).append(',');
        json.append("\"runtimeErrorCount\":").append(statistics.getRuntimeErrorCount()).append(',');
        json.append("\"timeoutCount\":").append(statistics.getTimeoutCount()).append(',');
        json.append("\"successRate\":").append(statistics.getSuccessRate()).append(',');
        json.append("\"failureRate\":").append(statistics.getFailureRate()).append(',');
        json.append("\"averageDurationMillis\":").append(statistics.getAverageDurationMillis()).append(',');
        json.append("\"aiRequestedCount\":").append(statistics.getAiRequestedCount()).append(',');
        json.append("\"aiAvailableCount\":").append(statistics.getAiAvailableCount()).append(',');
        json.append("\"aiUnavailableCount\":").append(statistics.getAiUnavailableCount());
        json.append('}');
        return json.toString();
    }

    /**
     * Extracts a single query parameter's value from a raw query string,
     * identical implementation to HistoryHandler's own — duplicated here
     * rather than shared, since both are small, private, single-purpose
     * helpers and extracting a shared utility for two 10-line methods
     * would be exactly the kind of speculative abstraction this project's
     * conventions have consistently avoided.
     *
     * @param query the raw query string; may be null
     * @param key    the parameter name to look up
     * @return the parameter's value, or null if not present
     */
    private String extractQueryParam(String query, String key) {
        if (query == null || query.isEmpty()) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String pairKey = (eq >= 0) ? pair.substring(0, eq) : pair;
            if (pairKey.equals(key)) {
                return (eq >= 0 && eq + 1 <= pair.length()) ? pair.substring(eq + 1) : "";
            }
        }
        return null;
    }
}