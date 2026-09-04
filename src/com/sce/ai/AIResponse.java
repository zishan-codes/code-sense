package com.sce.ai;

/**
 * AIResponse
 *
 * Immutable value object representing the outcome of an AI debugging-hint
 * request. Returned by AIConnector.fetchDebuggingHint() under EVERY
 * circumstance — including network failure, missing API key, and
 * malformed provider responses — so that callers (the AI UI Controller)
 * never need a separate exception-handling path just to render the
 * debugging panel. A failed AI call is a normal, expected outcome in
 * this system, not an exceptional one.
 *
 * Design intent for 'suggestedFix': this field is intentionally scoped
 * to a SHORT, targeted correction (e.g., "use .equals() instead of ==",
 * or a one-line change) rather than a fully rewritten method body. The
 * prompt sent by AIConnector explicitly constrains the model to this
 * scope — see AIConnector's prompt-building logic. This field may
 * legitimately be empty even when success == true, since not every
 * failure has an obvious single-line fix worth surfacing.
 *
 * @author Smart Code Evaluator Team
 */
public final class AIResponse {

    private final String hint;
    private final String suggestedFix;
    private final boolean success;

    /**
     * Constructs an immutable AIResponse.
     *
     * @param hint          root-cause explanation of the failure, in plain
     *                       language; must not be null (use an empty string,
     *                       never null, if there is no hint to show)
     * @param suggestedFix   an optional short, targeted correction pointer;
     *                       must not be null (use an empty string, never
     *                       null, if no such pointer applies)
     * @param success        true if a usable AI response was obtained; false
     *                       if the call failed for any reason (missing key,
     *                       network failure, malformed response, timeout).
     *                       When false, 'hint' should contain a short,
     *                       user-facing explanation of the unavailability
     *                       rather than being left blank.
     * @throws IllegalArgumentException if hint or suggestedFix is null
     */
    public AIResponse(String hint, String suggestedFix, boolean success) {
        if (hint == null) {
            throw new IllegalArgumentException("AIResponse: hint must not be null.");
        }
        if (suggestedFix == null) {
            throw new IllegalArgumentException("AIResponse: suggestedFix must not be null.");
        }
        this.hint = hint;
        this.suggestedFix = suggestedFix;
        this.success = success;
    }

    /**
     * Convenience factory for the fallback / unavailable case, used
     * consistently across every failure branch in AIConnector so the
     * UI always receives a uniformly-shaped, user-presentable response.
     *
     * @param reason short, human-readable explanation of why the AI hint
     *                could not be obtained (e.g., "AI hint unavailable —
     *                network error.")
     * @return an AIResponse with success=false and no suggestedFix
     */
    public static AIResponse unavailable(String reason) {
        String safeReason = (reason == null || reason.trim().isEmpty())
                ? "AI hint unavailable."
                : reason;
        return new AIResponse(safeReason, "", false);
    }

    /** @return the AI-generated root-cause explanation, or an unavailability message. */
    public String getHint() {
        return hint;
    }

    /** @return a short, targeted correction pointer, or an empty string if none was offered. */
    public String getSuggestedFix() {
        return suggestedFix;
    }

    /** @return true if this AIResponse represents a genuine, usable AI reply. */
    public boolean isSuccess() {
        return success;
    }

    @Override
    public String toString() {
        return "AIResponse{" +
                "success=" + success +
                ", hintLength=" + hint.length() +
                ", suggestedFixLength=" + suggestedFix.length() +
                '}';
    }
}