package com.sce.api;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JsonUtil
 *
 * Minimal, dependency-free JSON helper for the small, fixed-shape request
 * and response bodies used by the REST API foundation. This is NOT a
 * general-purpose JSON parser/serializer — it supports exactly two
 * operations: extracting a named string field from a flat JSON object,
 * and escaping a string value for safe embedding in a JSON document. This
 * mirrors the same hand-rolled, regex-based approach already used in
 * com.sce.ai.AIConnector for parsing provider responses — consistency
 * with an established project convention, not a new pattern.
 *
 * Deliberately NOT included (until a real future need proves otherwise):
 * nested object parsing, arrays, numeric/boolean field extraction, or a
 * generic Java-object-to-JSON serializer.
 *
 * @author Smart Code Evaluator Team
 */
final class JsonUtil {

    private JsonUtil() {
        // Static utility class - no instances.
    }

    /**
     * Extracts the string value of a named field from a flat JSON object,
     * e.g. given {"className":"Main","sourceCode":"..."} and fieldName
     * "className", returns "Main". Handles standard JSON string escape
     * sequences within the value (quote, backslash, newline, carriage
     * return, tab, and 4-hex-digit unicode escapes).
     *
     * @param json      the raw JSON document to search; must not be null
     * @param fieldName the field name to look up; must not be null
     * @return the field's unescaped string value, or null if json/fieldName
     *          is null, or if the field is not present, or if the field
     *          is not a JSON string value
     */
    static String extractStringField(String json, String fieldName) {
        if (json == null || fieldName == null) {
            return null;
        }

        Pattern pattern = Pattern.compile(
                "\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"",
                Pattern.DOTALL
        );
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            return null;
        }
        return unescapeJsonString(matcher.group(1));
    }

    /**
     * Escapes a string for safe embedding as a JSON string literal's
     * contents (i.e., the text to place between the surrounding quotes).
     *
     * @param raw the unescaped text; must not be null
     * @return the JSON-safe escaped text
     * @throws IllegalArgumentException if raw is null
     */
    static String escapeJsonString(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("JsonUtil.escapeJsonString: raw must not be null.");
        }

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
     * Reverses escapeJsonString()-style escaping on text extracted from a
     * JSON document via extractStringField().
     *
     * @param escaped the escaped text
     * @return the unescaped, literal text
     */
    private static String unescapeJsonString(String escaped) {
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
                    case 'u':
                        if (i + 5 < escaped.length()) {
                            String hex = escaped.substring(i + 2, i + 6);
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                                i += 5;
                            } catch (NumberFormatException nfe) {
                                sb.append(c);
                            }
                        } else {
                            sb.append(c);
                        }
                        break;
                    default: sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
