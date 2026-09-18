package com.sce.api;

import com.sce.core.EvaluationRecord;
import com.sce.history.EvaluationHistoryException;
import com.sce.history.EvaluationHistoryService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.List;

/**
 * HistoryHandler
 *
 * Handles both:
 *   GET /api/history            (optionally ?limit=N)
 *   GET /api/history/{evaluationId}
 *
 * registered under a single HttpServer context ("/api/history"), since
 * HttpServer routes any request whose path starts with that prefix to
 * this handler — the two sub-endpoints are distinguished internally by
 * inspecting the remainder of the request path after that prefix.
 *
 * This class contains NO SQL, NO JDBC, and NO direct dependency on
 * EvaluationRepository or SystemController — it depends only on
 * EvaluationHistoryService, exactly as the approved Day 5.3 scope
 * requires. It performs no evaluation/compilation logic of any kind.
 *
 * sourceCode is deliberately never included in any response this class
 * produces — history is for reviewing past outcomes, not re-fetching the
 * original submission.
 *
 * @author Smart Code Evaluator Team
 */
final class HistoryHandler implements HttpHandler {

    private static final String METHOD_GET = "GET";
    private static final String HISTORY_PATH = ApiServer.API_BASE_PATH + "/history";

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 100;

    private final EvaluationHistoryService evaluationHistoryService;

    /**
     * Constructs a HistoryHandler backed by the given
     * EvaluationHistoryService. Dependency injection is explicit here so
     * ApiServer owns and shares exactly one EvaluationHistoryService
     * instance across this handler (and any future handler that needs
     * it), rather than each handler constructing its own.
     *
     * @param evaluationHistoryService the service to delegate all
     *                                  history retrieval to; must not be null
     * @throws IllegalArgumentException if evaluationHistoryService is null
     */
    HistoryHandler(EvaluationHistoryService evaluationHistoryService) {
        if (evaluationHistoryService == null) {
            throw new IllegalArgumentException("HistoryHandler: evaluationHistoryService must not be null.");
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

            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getQuery();

            String evaluationId = extractEvaluationIdSegment(path);

            if (evaluationId != null) {
                handleGetById(exchange, evaluationId);
            } else {
                handleGetRecent(exchange, query);
            }

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
     * Determines whether the request path addresses a specific
     * evaluation ID (/api/history/{id}) or the collection endpoint
     * (/api/history or /api/history/). Uses the parsed URI path only —
     * never the raw request line — so a query string can never be
     * mistaken for part of an ID.
     *
     * @param path the request URI's path component (no query string)
     * @return the evaluation ID segment, or null if this request
     *          addresses the collection endpoint
     */
    private String extractEvaluationIdSegment(String path) {
        if (path == null || path.length() <= HISTORY_PATH.length()) {
            // Exactly "/api/history" (or shorter, which should not occur
            // given this handler is only ever invoked for that context)
            // — collection endpoint.
            return null;
        }

        String remainder = path.substring(HISTORY_PATH.length());
        if (!remainder.startsWith("/")) {
            // Path started with "/api/history" but continued with a
            // different, unexpected character (should not occur under
            // normal HttpServer routing) — treat as collection endpoint
            // rather than guessing at malformed input.
            return null;
        }

        String id = remainder.substring(1);
        if (id.isEmpty()) {
            // "/api/history/" — empty segment, handled safely as the
            // collection endpoint rather than an empty/invalid ID.
            return null;
        }

        return id;
    }

    /**
     * Handles GET /api/history (optionally ?limit=N): parses and
     * validates the limit query parameter, retrieves that many recent
     * evaluations via EvaluationHistoryService, and returns them as a
     * JSON array under the "evaluations" key.
     *
     * @param exchange the HTTP exchange to respond to
     * @param query     the raw query string (may be null)
     * @throws IOException if writing the response fails
     */
    private void handleGetRecent(HttpExchange exchange, String query) throws IOException {
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

        List<EvaluationRecord> records;
        try {
            records = evaluationHistoryService.getRecentEvaluations(limit);
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
                    "{\"error\":\"History could not be retrieved due to an internal error.\"}");
            return;
        }

        StringBuilder json = new StringBuilder(256);
        json.append("{\"evaluations\":[");
        for (int i = 0; i < records.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(toJson(records.get(i)));
        }
        json.append("]}");

        ApiResponse.sendJson(exchange, 200, json.toString());
    }

    /**
     * Handles GET /api/history/{evaluationId}: retrieves the specific
     * evaluation via EvaluationHistoryService and returns it as a single
     * JSON object, or HTTP 404 if no such evaluation exists.
     *
     * @param exchange     the HTTP exchange to respond to
     * @param evaluationId the evaluation ID extracted from the request path
     * @throws IOException if writing the response fails
     */
    private void handleGetById(HttpExchange exchange, String evaluationId) throws IOException {
        EvaluationRecord record;
        try {
            record = evaluationHistoryService.getEvaluationById(evaluationId);
        } catch (IllegalArgumentException e) {
            // Defensive: evaluationId is guaranteed non-empty by
            // extractEvaluationIdSegment(), so this should not occur.
            ApiResponse.sendJson(exchange, 400,
                    "{\"error\":\"" + JsonUtil.escapeJsonString(e.getMessage()) + "\"}");
            return;
        } catch (EvaluationHistoryException e) {
            ApiResponse.sendJson(exchange, 500,
                    "{\"error\":\"History could not be retrieved due to an internal error.\"}");
            return;
        }

        if (record == null) {
            ApiResponse.sendJson(exchange, 404, "{\"error\":\"Evaluation not found.\"}");
            return;
        }

        ApiResponse.sendJson(exchange, 200, toJson(record));
    }

    /**
     * Extracts a single query parameter's value from a raw query string
     * of the form "key1=value1&key2=value2". If the parameter appears
     * more than once, the first occurrence is used. Values are not
     * URL-decoded beyond what HttpExchange's URI already provides, since
     * the only parameter currently supported (limit) is expected to be
     * a plain decimal integer.
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

    /**
     * Serializes a single EvaluationRecord to a JSON object, deliberately
     * omitting sourceCode. Every String field is passed through
     * JsonUtil.escapeJsonString(); EvaluationRecord's own constructor
     * already guarantees none of these fields are null, but a null-safe
     * fallback to an empty string is applied anyway as defensive
     * insurance against any future relaxation of that guarantee.
     *
     * @param record the evaluation record to serialize
     * @return a JSON object string representing this record
     */
    private String toJson(EvaluationRecord record) {
        StringBuilder json = new StringBuilder(256);
        json.append('{');
        appendStringField(json, "evaluationId", record.getEvaluationId());
        json.append(',');
        appendStringField(json, "timestamp", record.getTimestamp() != null ? record.getTimestamp().toString() : null);
        json.append(',');
        appendStringField(json, "className", record.getClassName());
        json.append(',');
        appendStringField(json, "status", record.getStatus() != null ? record.getStatus().name() : null);
        json.append(',');
        json.append("\"exitCode\":").append(record.getExitCode());
        json.append(',');
        appendStringField(json, "stdout", record.getStdout());
        json.append(',');
        appendStringField(json, "stderr", record.getStderr());
        json.append(',');
        appendStringField(json, "aiRequestStatus",
                record.getAiRequestStatus() != null ? record.getAiRequestStatus().name() : null);
        json.append(',');
        appendStringField(json, "aiHint", record.getAiHint());
        json.append(',');
        appendStringField(json, "aiSuggestedFix", record.getAiSuggestedFix());
        json.append(',');
        json.append("\"durationMillis\":").append(record.getDurationMillis());
        json.append('}');
        return json.toString();
    }

    private void appendStringField(StringBuilder json, String fieldName, String value) {
        String safeValue = (value != null) ? value : "";
        json.append('"').append(fieldName).append("\":\"")
                .append(JsonUtil.escapeJsonString(safeValue))
                .append('"');
    }
}