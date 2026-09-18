package com.sce.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

/**
 * HealthHandler
 *
 * Handles GET /api/health, proving the API server is up and responding.
 * This is the only endpoint implemented in Day 5.1 — it performs no
 * business logic, touches no database, and has no dependency on any
 * other project package. Its sole purpose is to validate the REST API
 * transport foundation itself.
 *
 * METHOD HANDLING: only GET is accepted; any other HTTP method receives
 * HTTP 405. Unmatched paths never reach this handler at all — HttpServer
 * itself returns HTTP 404 for any path with no registered context, which
 * is verified directly against a real running server in ApiServerTest
 * rather than assumed.
 *
 * FAILURE HANDLING: the entire handling logic is wrapped in a try/catch
 * so that any unexpected failure while building the response results in
 * a generic HTTP 500 with no internal detail (stack trace, exception
 * message, or class name) exposed to the caller — diagnostics stay
 * server-side only, per the project's error-handling convention already
 * established for AIConnector and EvaluationRepository.
 *
 * @author Smart Code Evaluator Team
 */
final class HealthHandler implements HttpHandler {

    private static final String METHOD_GET = "GET";

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!METHOD_GET.equalsIgnoreCase(exchange.getRequestMethod())) {
                ApiResponse.sendJson(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }

            ApiResponse.sendJson(exchange, 200, "{\"status\":\"UP\"}");

        } catch (Exception unexpected) {
            // Deliberately generic: no exception message, class name, or
            // stack trace is sent to the client. Server-side logging of
            // the real cause belongs to a future logging stage, not
            // introduced here to keep Day 5.1 scope minimal.
            try {
                ApiResponse.sendJson(exchange, 500, "{\"error\":\"Internal Server Error\"}");
            } catch (IOException ioException) {
                // If we can't even write the 500 response, there is
                // nothing further this handler can safely do; the
                // exchange will be closed in the finally block below.
            }
        } finally {
            exchange.close();
        }
    }
}