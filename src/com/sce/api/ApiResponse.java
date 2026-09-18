package com.sce.api;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * ApiResponse
 *
 * Minimal, reusable helper for writing a JSON HTTP response with the
 * correct Content-Type, Content-Length, and UTF-8 encoding. This exists
 * because every future Day 5 endpoint (evaluations, history, analytics)
 * will need to write a JSON response in exactly this shape — centralizing
 * it here now avoids each future handler reimplementing header/encoding
 * logic, without building a full JSON-object/DTO framework that no
 * endpoint yet requires.
 *
 * This class intentionally does NOT parse or generate structured JSON
 * from Java objects — callers pass an already-formed JSON string. Adding
 * object-to-JSON serialization is deliberately deferred until a future
 * stage actually needs it (e.g. serializing an EvaluationRecord), to
 * avoid speculative complexity in this foundational stage.
 *
 * @author Smart Code Evaluator Team
 */
public final class ApiResponse {

    private static final String CONTENT_TYPE_JSON = "application/json; charset=utf-8";

    private ApiResponse() {
        // Static utility class - no instances.
    }

    /**
     * Writes a JSON response to the given HTTP exchange: sets the
     * Content-Type header, sends the status code and Content-Length via
     * sendResponseHeaders, writes the UTF-8-encoded body, and closes the
     * response body stream. Does NOT close the HttpExchange itself —
     * callers remain responsible for calling exchange.close() once all
     * response writing (and any further exchange usage) is complete.
     *
     * @param exchange    the HTTP exchange to respond to; must not be null
     * @param statusCode  the HTTP status code to send (e.g. 200, 404, 405, 500)
     * @param jsonBody    a complete, already-formed JSON document as a
     *                     String; must not be null
     * @throws IOException if writing the response fails
     */
    public static void sendJson(HttpExchange exchange, int statusCode, String jsonBody) throws IOException {
        if (exchange == null) {
            throw new IllegalArgumentException("ApiResponse.sendJson: exchange must not be null.");
        }
        if (jsonBody == null) {
            throw new IllegalArgumentException("ApiResponse.sendJson: jsonBody must not be null.");
        }

        byte[] bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", CONTENT_TYPE_JSON);
        exchange.sendResponseHeaders(statusCode, bodyBytes.length);

        try (OutputStream responseBody = exchange.getResponseBody()) {
            responseBody.write(bodyBytes);
        }
    }
}
