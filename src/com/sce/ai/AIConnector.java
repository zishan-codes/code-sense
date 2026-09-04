package com.sce.ai;

import com.sce.engine.Stage;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AIConnector
 *
 * Thin REST client responsible for turning a failed evaluation (source
 * code + captured error trace + Stage) into a bounded, structured prompt,
 * sending it to a configured LLM endpoint (Gemini-style by default), and
 * parsing the reply into an AIResponse.
 *
 * HARD DESIGN RULE (do not relax without team discussion): the prompt
 * built by buildPrompt() explicitly forbids the model from returning a
 * complete corrected implementation. It is scoped to (a) a plain-language
 * root-cause explanation and (b) an optional single-line correction
 * pointer. This is what keeps the tool a diagnostic aid rather than a
 * code-generation shortcut — see project SRS, Section 3.2.
 *
 * FAILURE PHILOSOPHY: every failure mode in this class — missing API
 * key, DNS/network failure, non-2xx HTTP status, timeout, or a reply
 * that doesn't match the expected format — is caught internally and
 * converted into AIResponse.unavailable(...). This class NEVER throws
 * out to the caller. A broken or absent AI provider must never be able
 * to crash or stall the core evaluation pipeline, which remains fully
 * usable (compiler output, stack traces) with or without AI availability.
 *
 * THREAD SAFETY: java.net.http.HttpClient instances are documented as
 * immutable and safe for concurrent use once built, so a single
 * AIConnector instance (and its single HttpClient) may be shared and
 * invoked concurrently by multiple evaluation threads without external
 * synchronization.
 *
 * @author Smart Code Evaluator Team
 */
public class AIConnector {

    /** Environment variable name used to source the provider API key.
     *  Keeping the key out of source code / config files committed to
     *  Git is a deliberate security choice for a student-facing tool. */
    private static final String API_KEY_ENV_VAR = "SCE_AI_API_KEY";

    /** Default Gemini-style generateContent endpoint. Overridable via
     *  the constructor for OpenAI-style deployments or local testing. */
    private static final String DEFAULT_ENDPOINT = "https://generativelanguage.googleapis.com/v1/models/gemini-3.6-flash:generateContent";

    /** Network-level timeout for the HTTP call itself. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(45);

    /** Caps applied to keep the prompt small, on-topic, and cheap in
     *  tokens — only the failing context is relevant, not the whole file. */
    private static final int MAX_SOURCE_CHARS = 4000;
    private static final int MAX_TRACE_CHARS = 2000;

    /** Minimal regex-based extraction of the generated text field from a
     *  Gemini-style JSON response body:
     *  { "candidates": [ { "content": { "parts": [ { "text": "..." } ] } } ] }
     *  NOTE: this is a deliberately narrow, dependency-free parser suited
     *  to this single known response shape. If the team later needs to
     *  support richer provider responses, swap this for a proper JSON
     *  library (e.g., org.json or Jackson) rather than expanding the regex. */
    private static final Pattern TEXT_FIELD_PATTERN =
            Pattern.compile("\"text\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"", Pattern.DOTALL);

    private final HttpClient httpClient;
    private final String apiKey;
    private final String endpoint;
    private final boolean configured;

    /**
     * Constructs an AIConnector using the default Gemini-style endpoint
     * and an API key sourced from the SCE_AI_API_KEY environment variable.
     * If the key is absent, the connector is still constructed successfully
     * (it does not throw) but is marked internally as unconfigured — every
     * call to fetchDebuggingHint() will then short-circuit to
     * AIResponse.unavailable(...) without attempting any network call.
     */
    public AIConnector() {
        this(DEFAULT_ENDPOINT,"AQ.Ab8RN6JZ8OmKiCjt4VnyVdAYIDam1kKKXvqXEmeq7phB5KuiMA");
    }

    /**
     * Constructs an AIConnector against an explicit endpoint and API key —
     * primarily useful for unit testing against a mock server, or for
     * pointing at an OpenAI-compatible endpoint instead of the default.
     *
     * @param endpoint the full URL of the LLM REST endpoint
     * @param apiKey    the provider API key, or null/blank if unavailable
     */
    public AIConnector(String endpoint, String apiKey) {
        this.endpoint = (endpoint == null || endpoint.trim().isEmpty()) ? DEFAULT_ENDPOINT : endpoint;
        this.apiKey = apiKey;
        this.configured = (apiKey != null && !apiKey.trim().isEmpty());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .version(HttpClient.Version.HTTP_2)
                .build();

        if (!this.configured) {
            // Logged, not thrown: an evaluator with no AI key configured
            // must still be fully usable for compile/run/timeout results.
            System.err.println(
                "AIConnector: no API key found in environment variable '" +
                API_KEY_ENV_VAR + "'. AI debugging hints will be reported " +
                "as unavailable until this is configured."
            );
        }
    }

    /**
     * Requests an AI-generated debugging hint for a failed evaluation.
     * Builds a bounded, stage-aware prompt from the supplied source and
     * error trace, sends it synchronously to the configured LLM endpoint,
     * and parses the structured reply.
     *
     * This method NEVER throws. Every failure path — missing configuration,
     * network error, timeout, non-2xx response, or unparseable reply — is
     * caught and converted into AIResponse.unavailable(reason), so it is
     * always safe to call from the AI UI Controller without an enclosing
     * try/catch.
     *
     * @param studentCode the full source code that was evaluated; may be
     *                     truncated internally to bound prompt size
     * @param errorTrace   the captured compiler diagnostic or runtime stack
     *                      trace text describing the failure; may be
     *                      truncated internally
     * @param stage         the pipeline stage at which the failure occurred
     *                       (COMPILE, RUNTIME, or TIMEOUT); used to select
     *                       stage-specific prompt framing
     * @return an AIResponse; check isSuccess() before relying on getHint()/
     *          getSuggestedFix() for a genuine AI-sourced explanation
     */
    public AIResponse fetchDebuggingHint(String studentCode, String errorTrace, Stage stage) {
        if (!configured) {
            return AIResponse.unavailable(
                "AI hint unavailable — no API key configured for this evaluator."
            );
        }
        if (stage == null) {
            return AIResponse.unavailable("AI hint unavailable — evaluation stage was not specified.");
        }

        String safeCode = studentCode == null ? "" : studentCode;
        String safeTrace = errorTrace == null ? "" : errorTrace;

        String prompt = buildPrompt(truncate(safeCode, MAX_SOURCE_CHARS),
                                     truncate(safeTrace, MAX_TRACE_CHARS),
                                     stage);

        String requestBody = buildRequestBody(prompt);

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + "?key=" + apiKey))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            
            // YEH LINE ADD KAR DE BHAI:
            System.out.println("DEBUG - Calling URL: " + endpoint);
            
        } catch (IllegalArgumentException e) {
            // Malformed endpoint URI — a configuration error, not a runtime
            // network failure, but still must not crash the pipeline.
            return AIResponse.unavailable("AI hint unavailable — invalid endpoint configuration.");
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            return AIResponse.unavailable("AI hint unavailable — request timed out.");
        } catch (java.io.IOException e) {
            // Covers DNS failure, connection refused, no internet connectivity, etc.
            return AIResponse.unavailable("AI hint unavailable — network error contacting AI provider.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return AIResponse.unavailable("AI hint unavailable — request was interrupted.");
        }
    //     System.out.println("\n--- RAW AI RESPONSE ---");
    // System.out.println(response.body());
    // System.out.println("-----------------------\n");
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            return AIResponse.unavailable("AI hint unavailable — API key was rejected by the provider.");
        }
        if (response.statusCode() == 429) {
            return AIResponse.unavailable("AI hint unavailable — provider rate limit exceeded.");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            // Yeh line asli error batayegi jo Google bhej raha hai
            System.err.println("\n--- GOOGLE API ERROR DETAILS ---");
            System.err.println(response.body());
            System.err.println("--------------------------------\n");
            
            return AIResponse.unavailable(
                "AI hint unavailable — provider returned HTTP " + response.statusCode() + "."
            );
        }

        return parseResponse(response.body());
    }

    /**
     * Builds the stage-aware, scope-constrained prompt text sent to the
     * LLM. The instructions here are the primary mechanism enforcing the
     * "hints, not full fixes" design rule — they are deliberately explicit
     * and repeated (root cause + optional short pointer, no full rewrite)
     * because LLMs left underspecified tend to default to emitting a
     * complete corrected solution.
     *
     * @param truncatedCode  source code, already bounded to MAX_SOURCE_CHARS
     * @param truncatedTrace  error trace, already bounded to MAX_TRACE_CHARS
     * @param stage            the failure stage, used to tailor framing
     * @return the full prompt text to send as the model's user turn
     */
    private String buildPrompt(String truncatedCode, String truncatedTrace, Stage stage) {
        String stageFraming;
        switch (stage) {
            case COMPILE:
                stageFraming = "The code below FAILED TO COMPILE. The text under " +
                        "'COMPILER OUTPUT' is javac's diagnostic output.";
                break;
            case RUNTIME:
                stageFraming = "The code below compiled successfully but THREW AN " +
                        "UNCAUGHT EXCEPTION at runtime. The text under 'ERROR TRACE' " +
                        "is the captured stack trace.";
                break;
            case TIMEOUT:
                stageFraming = "The code below was FORCIBLY TERMINATED because it did " +
                        "not finish within the execution time limit — this strongly " +
                        "suggests an infinite loop or an unbounded blocking call. The " +
                        "text under 'ERROR TRACE' is any output captured before termination.";
                break;
            default:
                stageFraming = "The code below failed during evaluation.";
        }

        return "You are assisting a second- or third-year Computer Science student " +
                "debug a Java program. You must NOT rewrite or provide a complete " +
                "corrected version of their code. Your job is strictly diagnostic.\n\n" +
                stageFraming + "\n\n" +
                "SOURCE CODE:\n" + truncatedCode + "\n\n" +
                (stage == Stage.COMPILE ? "COMPILER OUTPUT:\n" : "ERROR TRACE:\n") +
                truncatedTrace + "\n\n" +
                "Respond using EXACTLY this two-line format, with NO markdown formatting, " +
                "no asterisks, and no extra text:\n" +
                "HINT: Explain the exact root cause of the error in 1-2 sentences. Explicitly name the exception or error and explain why it happened based on the variable values.\n" +
                "SUGGESTED_FIX: Provide one short sentence explaining exactly what logical change is needed to fix this issue.";
    }

    /**
     * Serializes the prompt into a Gemini-style generateContent request
     * body. Manual string construction is used (no JSON library dependency)
     * since the request shape is small and fixed; JSON string values are
     * escaped defensively via escapeJson().
     *
     * @param prompt the full prompt text produced by buildPrompt()
     * @return a JSON request body string
     */
    private String buildRequestBody(String prompt) {
        return "{"
                + "\"contents\":[{"
                + "\"parts\":[{\"text\":\"" + escapeJson(prompt) + "\"}]"
                + "}],"
                + "\"generationConfig\":{"
                + "\"temperature\":0.2,"
                + "\"maxOutputTokens\":1024"
                + "}"
                + "}";
    }

    /**
     * Parses the LLM's JSON response body, extracts the generated text via
     * TEXT_FIELD_PATTERN, and splits it into the HINT / SUGGESTED_FIX
     * fields requested by the prompt format. Falls back to a generic,
     * clearly-labelled unavailable response if the reply cannot be parsed
     * into the expected shape — a malformed AI reply must degrade
     * gracefully, never propagate as an exception.
     *
     * @param responseBody raw JSON response body from the provider
     * @return a populated AIResponse, or AIResponse.unavailable(...) if
     *          the reply could not be parsed
     */
    private AIResponse parseResponse(String responseBody) {
        if (responseBody == null || responseBody.trim().isEmpty()) {
            return AIResponse.unavailable("AI hint unavailable — empty response from provider.");
        }

        Matcher matcher = TEXT_FIELD_PATTERN.matcher(responseBody);
        if (!matcher.find()) {
            return AIResponse.unavailable("AI hint unavailable — could not parse provider response.");
        }

        String rawText = unescapeJson(matcher.group(1));

        String hint = extractLabelledSection(rawText, "HINT:", "SUGGESTED_FIX:");
        String suggestedFix = extractLabelledSection(rawText, "SUGGESTED_FIX:", null);

        if (hint.isEmpty()) {
            // The model replied but did not follow the requested format
            // closely enough to extract a HINT section — surface the raw
            // text as a best-effort fallback rather than discarding it.
            hint = rawText.trim();
        }

        return new AIResponse(hint.trim(), suggestedFix.trim(), true);
    }

    /**
     * Extracts the text between a start label (inclusive of the label's
     * position) and an optional end label, trimming whitespace. Used to
     * pull the HINT and SUGGESTED_FIX sections out of the model's raw
     * two-line reply without a full parsing framework.
     *
     * @param text        the full raw reply text
     * @param startLabel   the label marking the start of the section, e.g. "HINT:"
     * @param endLabel     the label marking the start of the NEXT section, or
     *                      null if this section runs to the end of the text
     * @return the extracted section text, or an empty string if startLabel
     *          was not found
     */
    private String extractLabelledSection(String text, String startLabel, String endLabel) {
        int startIdx = text.indexOf(startLabel);
        if (startIdx == -1) {
            return "";
        }
        startIdx += startLabel.length();

        int endIdx = (endLabel != null) ? text.indexOf(endLabel, startIdx) : -1;
        if (endIdx == -1) {
            endIdx = text.length();
        }

        return text.substring(startIdx, endIdx).trim();
    }

    /**
     * Truncates text to at most maxChars, appending a marker so the model
     * (and any human reading logs) can tell the content was cut for
     * prompt-size reasons rather than the source genuinely ending there.
     *
     * @param text     the text to bound
     * @param maxChars the maximum number of characters to retain
     * @return the original text if within bounds, otherwise a truncated copy
     */
    private String truncate(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n... [truncated for length]";
    }

    /**
     * Escapes a string for safe embedding inside a JSON string literal.
     * Handles the characters required by the JSON spec for this use case;
     * intentionally minimal rather than a full JSON writer, matching the
     * "no external JSON library" constraint for this connector.
     *
     * @param raw the unescaped text
     * @return the JSON-safe escaped text
     */
    private String escapeJson(String raw) {
        StringBuilder sb = new StringBuilder(raw.length() + 16);
        for (char c : raw.toCharArray()) {
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    /**
     * Reverses escapeJson()-style escaping on text extracted from a
     * provider's JSON response, so the hint text rendered to the user
     * does not contain literal backslash-n sequences etc.
     *
     * @param escaped the escaped text extracted via TEXT_FIELD_PATTERN
     * @return the unescaped, human-readable text
     */
    private String unescapeJson(String escaped) {
        StringBuilder sb = new StringBuilder(escaped.length());
        for (int i = 0; i < escaped.length(); i++) {
            char c = escaped.charAt(i);
            if (c == '\\' && i + 1 < escaped.length()) {
                char next = escaped.charAt(i + 1);
                switch (next) {
                    case 'n': sb.append('\n'); i++; break;
                    case 'r': sb.append('\r'); i++; break;
                    case 't': sb.append('\t'); i++; break;
                    case '"': sb.append('"'); i++; break;
                    case '\\': sb.append('\\'); i++; break;
                    default: sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}