package com.sce.api;

import com.sce.SystemController;
import com.sce.core.EvaluationRecord;
import com.sce.core.EvaluationRequest;
import com.sce.engine.EvaluationException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * EvaluateHandler
 *
 * Handles POST /api/evaluate: accepts a JSON request containing a Java
 * class name and source code, submits it through the EXISTING
 * SystemController pipeline (unchanged), and returns a JSON evaluation
 * result.
 *
 * This class contains NO compilation, execution, timeout, or AI logic of
 * its own — every one of those responsibilities remains entirely inside
 * SystemController / ExecutionEngine / AIConnector. This handler's only
 * job is translating between the HTTP/JSON world and
 * SystemController.submitAndAwaitResult().
 *
 * CORRELATION: this handler does NOT poll SystemController.
 * getLastEvaluationRecord() and does NOT read any shared mutable state
 * to determine its result. submitAndAwaitResult() returns the
 * EvaluationRecord belonging to THIS specific submission as a genuine
 * method return value (backed internally by a per-call
 * CompletableFuture), so concurrent callers — including the Swing UI
 * submitting through the existing handleSubmission() path at the same
 * time — can never observe or receive each other's results.
 *
 * CONCURRENCY: no lock, no polling loop, and no new thread or executor
 * is introduced here. The calling thread (provided by HttpServer's own
 * internal executor) simply blocks inside submitAndAwaitResult() until
 * that specific evaluation completes or the timeout elapses — the same
 * single worker thread and queue inside SystemController continue to
 * process all evaluations (from both the API and the Swing UI) strictly
 * one at a time, in submission order, exactly as before this class
 * existed.
 *
 * @author Smart Code Evaluator Team
 */
final class EvaluateHandler implements HttpHandler {

    private static final String METHOD_POST = "POST";

    /**
     * Upper bound on how long this handler will wait for an evaluation to
     * complete before giving up and returning HTTP 500. Chosen generously
     * above the sum of the engine's own hard limits: ExecutionEngine's
     * 3000ms execution watchdog + AIConnector's 8-second HTTP timeout +
     * compilation time + margin.
     */
    private static final long AWAIT_TIMEOUT_SECONDS = 60L;

    /** Upper bound on request body size read into memory, to avoid an
     *  unbounded read from a malicious or malformed client. */
    private static final int MAX_REQUEST_BODY_BYTES = 2_000_000;

    private final SystemController systemController;

    /**
     * Constructs an EvaluateHandler bound to the given, already-started
     * SystemController instance. This handler never constructs its own
     * SystemController — doing so would create a second, independent
     * evaluation pipeline (separate worker thread and queue) running
     * alongside whatever the Swing UI already owns.
     *
     * @param systemController the shared SystemController instance to
     *                           submit evaluations through; must not be null
     * @throws IllegalArgumentException if systemController is null
     */
    EvaluateHandler(SystemController systemController) {
        if (systemController == null) {
            throw new IllegalArgumentException("EvaluateHandler: systemController must not be null.");
        }
        this.systemController = systemController;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!METHOD_POST.equalsIgnoreCase(exchange.getRequestMethod())) {
                ApiResponse.sendJson(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }

            String requestBody;
            try {
                requestBody = readRequestBody(exchange.getRequestBody());
            } catch (RequestTooLargeException e) {
                ApiResponse.sendJson(exchange, 400, "{\"error\":\"Request body too large.\"}");
                return;
            }

            String className = JsonUtil.extractStringField(requestBody, "className");
            String sourceCode = JsonUtil.extractStringField(requestBody, "sourceCode");

            EvaluationRequest evaluationRequest;
            try {
                // Reuses EvaluationRequest's OWN existing validation
                // (non-null, non-blank) rather than duplicating that
                // logic here — a malformed/incomplete JSON body simply
                // surfaces as EvaluationRequest's own
                // IllegalArgumentException, mapped to HTTP 400 below.
                evaluationRequest = new EvaluationRequest(className, sourceCode);
            } catch (IllegalArgumentException e) {
                ApiResponse.sendJson(exchange, 400,
                        "{\"error\":\"" + JsonUtil.escapeJsonString(e.getMessage()) + "\"}");
                return;
            }

            EvaluationRecord record;
            try {
                record = systemController.submitAndAwaitResult(
                        evaluationRequest, AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS
                );
            } catch (IllegalStateException e) {
                // SystemController.submitAndAwaitResult() throws this if
                // the controller has not been started — an API-level
                // "service not ready" condition, not a client error.
                ApiResponse.sendJson(exchange, 503,
                        "{\"error\":\"Evaluation service is not currently available.\"}");
                return;
            } catch (TimeoutException e) {
                ApiResponse.sendJson(exchange, 500,
                        "{\"error\":\"Evaluation did not complete within the expected time.\"}");
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ApiResponse.sendJson(exchange, 500,
                        "{\"error\":\"Evaluation was interrupted before completion.\"}");
                return;
            } catch (IOException e) {
                // Engine-level environment failure (e.g. workspace
                // creation/write failure). No internal detail (file
                // paths, exception message) is exposed to the client.
                ApiResponse.sendJson(exchange, 500,
                        "{\"error\":\"Evaluation could not be completed due to an internal error.\"}");
                return;
            } catch (EvaluationException e) {
                // Engine-level failure (e.g. javac/java could not be
                // launched). Same principle: no internal detail exposed.
                ApiResponse.sendJson(exchange, 500,
                        "{\"error\":\"Evaluation could not be completed due to an internal error.\"}");
                return;
            }

            ApiResponse.sendJson(exchange, 200, toResponseJson(record));

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
     * Builds the JSON response body from a completed EvaluationRecord.
     * Source code is deliberately NOT echoed back in the response — the
     * client already has it, and omitting it keeps the response body
     * proportional to the evaluation's output rather than its input.
     *
     * @param record the completed evaluation record
     * @return a JSON document describing the evaluation outcome
     */
    private String toResponseJson(EvaluationRecord record) {
        StringBuilder json = new StringBuilder(256);
        json.append('{');
        appendJsonStringField(json, "evaluationId", record.getEvaluationId(), true);
        appendJsonStringField(json, "className", record.getClassName(), true);
        appendJsonStringField(json, "status", record.getStatus().name(), true);
        json.append("\"exitCode\":").append(record.getExitCode()).append(',');
        appendJsonStringField(json, "stdout", record.getStdout(), true);
        appendJsonStringField(json, "stderr", record.getStderr(), true);
        appendJsonStringField(json, "aiRequestStatus", record.getAiRequestStatus().name(), true);
        appendJsonStringField(json, "aiHint", record.getAiHint(), true);
        appendJsonStringField(json, "aiSuggestedFix", record.getAiSuggestedFix(), true);
        json.append("\"durationMillis\":").append(record.getDurationMillis());
        json.append('}');
        return json.toString();
    }

    private void appendJsonStringField(StringBuilder json, String fieldName, String value, boolean trailingComma) {
        json.append('"').append(fieldName).append("\":\"")
                .append(JsonUtil.escapeJsonString(value))
                .append('"');
        if (trailingComma) {
            json.append(',');
        }
    }

    /**
     * Reads the full request body into a String, bounded by
     * MAX_REQUEST_BODY_BYTES to avoid an unbounded in-memory read.
     *
     * @param inputStream the exchange's request body stream
     * @return the request body decoded as UTF-8
     * @throws IOException              if reading fails
     * @throws RequestTooLargeException if the body exceeds the size limit
     */
    private String readRequestBody(InputStream inputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int bytesRead;
        int totalBytes = 0;

        while ((bytesRead = inputStream.read(chunk)) != -1) {
            totalBytes += bytesRead;
            if (totalBytes > MAX_REQUEST_BODY_BYTES) {
                throw new RequestTooLargeException();
            }
            buffer.write(chunk, 0, bytesRead);
        }

        return buffer.toString(StandardCharsets.UTF_8);
    }

    /** Thrown internally when the request body exceeds the configured size limit. */
    private static final class RequestTooLargeException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
