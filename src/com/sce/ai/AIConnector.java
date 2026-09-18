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
 * HARD DESIGN RULE: the prompt built by buildPrompt() explicitly forbids
 * the model from returning a complete corrected implementation.
 *
 * FAILURE PHILOSOPHY: every failure mode in this class is caught internally
 * and converted into AIResponse.unavailable(...). A broken or absent AI
 * provider must never be able to crash or stall the core evaluation pipeline.
 *
 * THREAD SAFETY: a single HttpClient instance may be safely shared by
 * concurrent evaluation threads.
 *
 * @author Smart Code Evaluator Team
 */
public class AIConnector {

    /**
     * Environment variable used to source the provider API key.
     *
     * The key is deliberately NOT stored in source code.
     */
    private static final String API_KEY_ENV_VAR = "SCE_AI_API_KEY";

    /**
     * Default Gemini generateContent endpoint.
     */
    private static final String DEFAULT_ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent";

    /** Network-level timeout for the HTTP call itself. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(45);

    /** Bounds applied to source code and error trace sent to the AI provider. */
    private static final int MAX_SOURCE_CHARS = 4000;
    private static final int MAX_TRACE_CHARS = 2000;

    /**
     * Minimal regex-based extraction of generated text from a Gemini-style
     * JSON response.
     */
    private static final Pattern TEXT_FIELD_PATTERN =
            Pattern.compile(
                    "\"text\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"",
                    Pattern.DOTALL
            );

    private final HttpClient httpClient;
    private final String apiKey;
    private final String endpoint;
    private final boolean configured;

    /**
     * Constructs an AIConnector using the default Gemini endpoint and
     * reads the API key from the SCE_AI_API_KEY environment variable.
     *
     * No API key is stored in source code.
     */
    public AIConnector() {
        this(DEFAULT_ENDPOINT, System.getenv(API_KEY_ENV_VAR));
    }

    /**
     * Constructs an AIConnector against an explicit endpoint and API key.
     *
     * Primarily useful for testing with a mock server or for future
     * provider-specific endpoint configuration.
     *
     * @param endpoint the full URL of the LLM REST endpoint
     * @param apiKey provider API key, or null/blank if unavailable
     */
    public AIConnector(String endpoint, String apiKey) {
        this.endpoint =
                (endpoint == null || endpoint.trim().isEmpty())
                        ? DEFAULT_ENDPOINT
                        : endpoint;

        this.apiKey = apiKey;

        this.configured =
                apiKey != null && !apiKey.trim().isEmpty();

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .version(HttpClient.Version.HTTP_2)
                .build();

        if (!this.configured) {
            System.err.println(
                    "AIConnector: no API key found in environment variable '" +
                    API_KEY_ENV_VAR +
                    "'. AI debugging hints will be reported as unavailable " +
                    "until this is configured."
            );
        }
    }

    /**
     * Requests an AI-generated debugging hint for a failed evaluation.
     *
     * This method NEVER throws provider/network errors to the caller.
     */
    public AIResponse fetchDebuggingHint(
            String studentCode,
            String errorTrace,
            Stage stage) {

        if (!configured) {
            return AIResponse.unavailable(
                    "AI hint unavailable — no API key configured for this evaluator."
            );
        }

        if (stage == null) {
            return AIResponse.unavailable(
                    "AI hint unavailable — evaluation stage was not specified."
            );
        }

        String safeCode =
                studentCode == null ? "" : studentCode;

        String safeTrace =
                errorTrace == null ? "" : errorTrace;

        String prompt = buildPrompt(
                truncate(safeCode, MAX_SOURCE_CHARS),
                truncate(safeTrace, MAX_TRACE_CHARS),
                stage
        );

        String requestBody = buildRequestBody(prompt);

        HttpRequest request;

        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("x-goog-api-key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

        } catch (IllegalArgumentException e) {
            return AIResponse.unavailable(
                    "AI hint unavailable — invalid endpoint configuration."
            );
        }

        HttpResponse<String> response;

        try {
            response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

        } catch (HttpTimeoutException e) {
            return AIResponse.unavailable(
                    "AI hint unavailable — request timed out."
            );

        } catch (java.io.IOException e) {
            return AIResponse.unavailable(
                    "AI hint unavailable — network error contacting AI provider."
            );

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            return AIResponse.unavailable(
                    "AI hint unavailable — request was interrupted."
            );
        }

        if (response.statusCode() == 401 ||
            response.statusCode() == 403) {

            return AIResponse.unavailable(
                    "AI hint unavailable — API key was rejected by the provider."
            );
        }

        if (response.statusCode() == 429) {
            return AIResponse.unavailable(
                    "AI hint unavailable — provider rate limit exceeded."
            );
        }

        if (response.statusCode() < 200 ||
            response.statusCode() >= 300) {

            return AIResponse.unavailable(
                    "AI hint unavailable — provider returned HTTP " +
                    response.statusCode() + "."
            );
        }

        return parseResponse(response.body());
    }

    /**
     * Builds the stage-aware, scope-constrained prompt.
     */
    private String buildPrompt(
            String truncatedCode,
            String truncatedTrace,
            Stage stage) {

        String stageFraming;

        switch (stage) {
            case COMPILE:
                stageFraming =
                        "The code below FAILED TO COMPILE. The text under " +
                        "'COMPILER OUTPUT' is javac's diagnostic output.";
                break;

            case RUNTIME:
                stageFraming =
                        "The code below compiled successfully but THREW AN " +
                        "UNCAUGHT EXCEPTION at runtime. The text under " +
                        "'ERROR TRACE' is the captured stack trace.";
                break;

            case TIMEOUT:
                stageFraming =
                        "The code below was FORCIBLY TERMINATED because it did " +
                        "not finish within the execution time limit — this strongly " +
                        "suggests an infinite loop or an unbounded blocking call. " +
                        "The text under 'ERROR TRACE' is any output captured before termination.";
                break;

            default:
                stageFraming =
                        "The code below failed during evaluation.";
        }

        return
                "You are assisting a second- or third-year Computer Science student " +
                "debug a Java program. You must NOT rewrite or provide a complete " +
                "corrected version of their code. Your job is strictly diagnostic.\n\n" +

                stageFraming + "\n\n" +

                "SOURCE CODE:\n" +
                truncatedCode + "\n\n" +

                (stage == Stage.COMPILE
                        ? "COMPILER OUTPUT:\n"
                        : "ERROR TRACE:\n") +

                truncatedTrace + "\n\n" +

                "Respond using EXACTLY this two-line format, with NO markdown formatting, " +
                "no asterisks, and no extra text:\n" +

                "HINT: Explain the exact root cause of the error in 1-2 sentences. " +
                "Explicitly name the exception or error and explain why it happened " +
                "based on the variable values.\n" +

                "SUGGESTED_FIX: Provide one short sentence explaining exactly what " +
                "logical change is needed to fix this issue.";
    }

    /**
     * Serializes the prompt into a Gemini-style request body.
     */
    private String buildRequestBody(String prompt) {

        return "{"
                + "\"contents\":[{"
                + "\"parts\":[{\"text\":\""
                + escapeJson(prompt)
                + "\"}]"
                + "}],"
                + "\"generationConfig\":{"
                + "\"temperature\":0.2,"
                + "\"maxOutputTokens\":1024"
                + "}"
                + "}";
    }

    /**
     * Parses the provider response and extracts HINT / SUGGESTED_FIX.
     */
    private AIResponse parseResponse(String responseBody) {

        if (responseBody == null ||
            responseBody.trim().isEmpty()) {

            return AIResponse.unavailable(
                    "AI hint unavailable — empty response from provider."
            );
        }

        Matcher matcher =
                TEXT_FIELD_PATTERN.matcher(responseBody);

        if (!matcher.find()) {
            return AIResponse.unavailable(
                    "AI hint unavailable — could not parse provider response."
            );
        }

        String rawText =
                unescapeJson(matcher.group(1));

        String hint =
                extractLabelledSection(
                        rawText,
                        "HINT:",
                        "SUGGESTED_FIX:"
                );

        String suggestedFix =
                extractLabelledSection(
                        rawText,
                        "SUGGESTED_FIX:",
                        null
                );

        if (hint.isEmpty()) {
            hint = rawText.trim();
        }

        return new AIResponse(
                hint.trim(),
                suggestedFix.trim(),
                true
        );
    }

    /**
     * Extracts text between two labels.
     */
    private String extractLabelledSection(
            String text,
            String startLabel,
            String endLabel) {

        int startIdx =
                text.indexOf(startLabel);

        if (startIdx == -1) {
            return "";
        }

        startIdx += startLabel.length();

        int endIdx =
                endLabel != null
                        ? text.indexOf(endLabel, startIdx)
                        : -1;

        if (endIdx == -1) {
            endIdx = text.length();
        }

        return text
                .substring(startIdx, endIdx)
                .trim();
    }

    /**
     * Truncates text to the configured maximum size.
     */
    private String truncate(
            String text,
            int maxChars) {

        if (text.length() <= maxChars) {
            return text;
        }

        return text.substring(0, maxChars)
                + "\n... [truncated for length]";
    }

    /**
     * Escapes a string for JSON.
     */
    private String escapeJson(String raw) {

        StringBuilder sb =
                new StringBuilder(raw.length() + 16);

        for (char c : raw.toCharArray()) {

            switch (c) {

                case '"':
                    sb.append("\\\"");
                    break;

                case '\\':
                    sb.append("\\\\");
                    break;

                case '\n':
                    sb.append("\\n");
                    break;

                case '\r':
                    sb.append("\\r");
                    break;

                case '\t':
                    sb.append("\\t");
                    break;

                default:
                    if (c < 0x20) {
                        sb.append(
                                String.format(
                                        "\\u%04x",
                                        (int) c
                                )
                        );
                    } else {
                        sb.append(c);
                    }
            }
        }

        return sb.toString();
    }

    /**
     * Reverses JSON escaping for provider response text.
     */
    private String unescapeJson(String escaped) {

        StringBuilder sb =
                new StringBuilder(escaped.length());

        for (int i = 0; i < escaped.length(); i++) {

            char c = escaped.charAt(i);

            if (c == '\\' && i + 1 < escaped.length()) {

                char next =
                        escaped.charAt(i + 1);

                switch (next) {

                    case 'n':
                        sb.append('\n');
                        i++;
                        break;

                    case 'r':
                        sb.append('\r');
                        i++;
                        break;

                    case 't':
                        sb.append('\t');
                        i++;
                        break;

                    case '"':
                        sb.append('"');
                        i++;
                        break;

                    case '\\':
                        sb.append('\\');
                        i++;
                        break;

                    default:
                        sb.append(c);
                }

            } else {
                sb.append(c);
            }
        }

        return sb.toString();
    }
}