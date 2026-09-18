package com.sce.api;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

/**
 * CorsHandler
 *
 * Wraps an existing HttpHandler to add browser CORS support, without any
 * change to the wrapped handler itself. This is the single, centralized
 * place CORS is implemented in this project — every context in ApiServer
 * is registered as new CorsHandler(new XxxHandler(...)) rather than each
 * handler duplicating this logic.
 *
 * BEHAVIOR:
 *   1. CORS response headers (Allow-Origin, Allow-Methods, Allow-Headers,
 *      Max-Age) are set on every request, echoing the caller's own
 *      Origin header back rather than using a blanket "*" — this is the
 *      standard, more-precise pattern for local development across
 *      multiple ports (e.g. a frontend on :5500 calling an API on
 *      :8080) without granting every possible origin unconditional
 *      access. If a request has no Origin header at all (e.g. a plain
 *      curl call, or same-origin access), no Allow-Origin header is set
 *      — harmless, since such callers don't need or check it.
 *   2. If the request method is OPTIONS (the browser's CORS preflight),
 *      this handler responds directly with HTTP 204 and an empty body,
 *      and returns WITHOUT EVER CALLING the wrapped handler. This is
 *      what guarantees a preflight can never trigger evaluation,
 *      compilation, AI, database, history, or analytics logic — the
 *      delegate's handle() method is simply never invoked for OPTIONS.
 *   3. For every other method (GET, POST, ...), the CORS headers are set
 *      and the wrapped handler runs exactly as it did before this class
 *      existed — same validation, same status codes, same response
 *      bodies, same exception handling. This class writes no JSON body
 *      of its own for non-OPTIONS requests and does not close the
 *      exchange in that path; the delegate remains fully responsible
 *      for its own response and its own exchange.close(), exactly as
 *      today.
 *
 * NO new thread, executor, queue, or business logic is introduced here —
 * this class only reads/sets HTTP headers and conditionally short-circuits
 * for OPTIONS.
 *
 * @author Smart Code Evaluator Team
 */
final class CorsHandler implements HttpHandler {

    private static final String HEADER_ORIGIN = "Origin";
    private static final String HEADER_ALLOW_ORIGIN = "Access-Control-Allow-Origin";
    private static final String HEADER_ALLOW_METHODS = "Access-Control-Allow-Methods";
    private static final String HEADER_ALLOW_HEADERS = "Access-Control-Allow-Headers";
    private static final String HEADER_MAX_AGE = "Access-Control-Max-Age";

    private static final String ALLOWED_METHODS = "GET, POST, OPTIONS";
    private static final String ALLOWED_HEADERS = "Content-Type";
    private static final String MAX_AGE_SECONDS = "86400"; // 24h — reduces repeated preflights during a demo/dev session

    private static final String METHOD_OPTIONS = "OPTIONS";

    private final HttpHandler delegate;

    /**
     * Wraps the given handler with CORS support.
     *
     * @param delegate the real handler to invoke for non-OPTIONS requests;
     *                  must not be null
     * @throws IllegalArgumentException if delegate is null
     */
    CorsHandler(HttpHandler delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("CorsHandler: delegate must not be null.");
        }
        this.delegate = delegate;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        applyCorsHeaders(exchange);

        if (METHOD_OPTIONS.equalsIgnoreCase(exchange.getRequestMethod())) {
            // Preflight: answered here, directly, with no body — the
            // wrapped handler (and everything it depends on:
            // SystemController, EvaluationHistoryService, the database,
            // AI, analytics) is never reached for this request.
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }

        delegate.handle(exchange);
    }

    /**
     * Sets the CORS response headers on the given exchange. Must be
     * called before any response is sent (i.e. before
     * sendResponseHeaders()), since HttpExchange's response headers can
     * only be modified up to that point — calling this first, before
     * delegating, guarantees that.
     *
     * @param exchange the exchange to set headers on
     */
    private void applyCorsHeaders(HttpExchange exchange) {
        Headers requestHeaders = exchange.getRequestHeaders();
        Headers responseHeaders = exchange.getResponseHeaders();

        String origin = requestHeaders.getFirst(HEADER_ORIGIN);
        if (origin != null && !origin.isEmpty()) {
            // Echo the caller's own origin rather than "*": this permits
            // the frontend regardless of which local port it happens to
            // be served from (e.g. 5500, 5501, 3000), without granting
            // blanket access to every origin unconditionally. No
            // Access-Control-Allow-Credentials header is set, and this
            // frontend never sends credentials, so this remains
            // cookie/auth-free per the project's constraints.
            responseHeaders.set(HEADER_ALLOW_ORIGIN, origin);
        }

        responseHeaders.set(HEADER_ALLOW_METHODS, ALLOWED_METHODS);
        responseHeaders.set(HEADER_ALLOW_HEADERS, ALLOWED_HEADERS);
        responseHeaders.set(HEADER_MAX_AGE, MAX_AGE_SECONDS);
    }
}