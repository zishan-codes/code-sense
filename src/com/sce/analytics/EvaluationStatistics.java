package com.sce.analytics;

import java.util.Objects;

/**
 * EvaluationStatistics
 *
 * Immutable data model representing a set of aggregated evaluation
 * statistics. This class performs NO calculation of its own — it is a
 * pure result/value object. A future analytics engine (not part of Day
 * 4.1) is responsible for computing these values from stored
 * EvaluationRecord data and constructing instances of this class.
 *
 * SEMANTIC MAPPING (for documentation purposes only — this class has no
 * compile-time dependency on either enum):
 *   successfulEvaluations  corresponds to Result.Status.SUCCESS
 *   compileErrorCount      corresponds to Result.Status.COMPILE_ERROR
 *   runtimeErrorCount      corresponds to Result.Status.RUNTIME_ERROR
 *   timeoutCount           corresponds to Result.Status.TIMEOUT
 *   aiAvailableCount       corresponds to AiRequestStatus.AVAILABLE
 *   aiUnavailableCount     corresponds to AiRequestStatus.UNAVAILABLE
 *   aiRequestedCount       = aiAvailableCount + aiUnavailableCount
 *                            (i.e. every evaluation where the AI was
 *                            actually called, regardless of outcome).
 *                            Evaluations with AiRequestStatus.NOT_REQUESTED
 *                            are not separately counted by a field on
 *                            this class — they are derivable as
 *                            (totalEvaluations - aiRequestedCount).
 *
 * RATES: successRate and failureRate are fractions in the closed range
 * [0.0, 1.0], not 0-100 percentages. When totalEvaluations > 0, the two
 * must sum to 1.0 (within floating-point tolerance). When
 * totalEvaluations == 0, both are exactly 0.0 — there is no rate to
 * report over an empty dataset.
 *
 * ZERO-EVALUATION CASE: an empty dataset is a fully valid state, not a
 * special case requiring a different type. When totalEvaluations == 0,
 * every count field must be 0, both rates must be 0.0, and
 * averageDurationMillis must be 0.0. The static empty() factory returns
 * this canonical instance for convenience.
 *
 * IMMUTABILITY: every field is private and final, there are no setters,
 * and the constructor validates every argument so that no invalid or
 * internally-inconsistent instance can ever be constructed. This class
 * has no dependency on Swing, JDBC, database classes, or any other
 * project package — only standard Java.
 *
 * @author Smart Code Evaluator Team
 */
public final class EvaluationStatistics {

    private static final double RATE_TOLERANCE = 1e-9;

    private final int totalEvaluations;
    private final int successfulEvaluations;
    private final int compileErrorCount;
    private final int runtimeErrorCount;
    private final int timeoutCount;
    private final double successRate;
    private final double failureRate;
    private final double averageDurationMillis;
    private final int aiRequestedCount;
    private final int aiAvailableCount;
    private final int aiUnavailableCount;

    /**
     * Constructs an immutable, internally-consistent EvaluationStatistics
     * instance. All arguments are validated; any inconsistency causes an
     * IllegalArgumentException rather than producing an invalid object.
     *
     * @param totalEvaluations       total number of evaluations included
     *                                in this statistics snapshot; must be
     *                                &gt;= 0
     * @param successfulEvaluations  count of evaluations with
     *                                Result.Status.SUCCESS; must be &gt;= 0
     * @param compileErrorCount      count of evaluations with
     *                                Result.Status.COMPILE_ERROR; must be
     *                                &gt;= 0
     * @param runtimeErrorCount      count of evaluations with
     *                                Result.Status.RUNTIME_ERROR; must be
     *                                &gt;= 0
     * @param timeoutCount           count of evaluations with
     *                                Result.Status.TIMEOUT; must be &gt;= 0
     * @param successRate            fraction of evaluations that succeeded,
     *                                in [0.0, 1.0]
     * @param failureRate            fraction of evaluations that did not
     *                                succeed, in [0.0, 1.0]; must sum with
     *                                successRate to 1.0 when
     *                                totalEvaluations &gt; 0, or be 0.0
     *                                when totalEvaluations == 0
     * @param averageDurationMillis  average wall-clock duration, in
     *                                milliseconds, of the included
     *                                evaluations; must be &gt;= 0.0, and
     *                                exactly 0.0 when totalEvaluations == 0
     * @param aiRequestedCount       count of evaluations for which the AI
     *                                was actually called (AVAILABLE +
     *                                UNAVAILABLE); must be &gt;= 0 and
     *                                &lt;= totalEvaluations
     * @param aiAvailableCount       count of evaluations with
     *                                AiRequestStatus.AVAILABLE; must be &gt;= 0
     * @param aiUnavailableCount     count of evaluations with
     *                                AiRequestStatus.UNAVAILABLE; must be
     *                                &gt;= 0
     * @throws IllegalArgumentException if any count is negative, if the
     *                                    four status counts do not sum
     *                                    exactly to totalEvaluations, if
     *                                    aiAvailableCount + aiUnavailableCount
     *                                    does not equal aiRequestedCount,
     *                                    if aiRequestedCount exceeds
     *                                    totalEvaluations, if either rate
     *                                    is outside [0.0, 1.0], if the
     *                                    rates do not sum to 1.0 (within
     *                                    tolerance) for a non-empty
     *                                    dataset, or if the zero-evaluation
     *                                    invariants described above are
     *                                    violated
     */
    public EvaluationStatistics(int totalEvaluations,
                                 int successfulEvaluations,
                                 int compileErrorCount,
                                 int runtimeErrorCount,
                                 int timeoutCount,
                                 double successRate,
                                 double failureRate,
                                 double averageDurationMillis,
                                 int aiRequestedCount,
                                 int aiAvailableCount,
                                 int aiUnavailableCount) {

        requireNonNegative(totalEvaluations, "totalEvaluations");
        requireNonNegative(successfulEvaluations, "successfulEvaluations");
        requireNonNegative(compileErrorCount, "compileErrorCount");
        requireNonNegative(runtimeErrorCount, "runtimeErrorCount");
        requireNonNegative(timeoutCount, "timeoutCount");
        requireNonNegative(aiRequestedCount, "aiRequestedCount");
        requireNonNegative(aiAvailableCount, "aiAvailableCount");
        requireNonNegative(aiUnavailableCount, "aiUnavailableCount");

        int statusSum = successfulEvaluations + compileErrorCount + runtimeErrorCount + timeoutCount;
        if (statusSum != totalEvaluations) {
            throw new IllegalArgumentException(
                "EvaluationStatistics: successfulEvaluations + compileErrorCount + " +
                "runtimeErrorCount + timeoutCount (" + statusSum + ") must equal " +
                "totalEvaluations (" + totalEvaluations + ")."
            );
        }

        int aiOutcomeSum = aiAvailableCount + aiUnavailableCount;
        if (aiOutcomeSum != aiRequestedCount) {
            throw new IllegalArgumentException(
                "EvaluationStatistics: aiAvailableCount + aiUnavailableCount (" + aiOutcomeSum +
                ") must equal aiRequestedCount (" + aiRequestedCount + ")."
            );
        }

        if (aiRequestedCount > totalEvaluations) {
            throw new IllegalArgumentException(
                "EvaluationStatistics: aiRequestedCount (" + aiRequestedCount +
                ") must not exceed totalEvaluations (" + totalEvaluations + ")."
            );
        }

        if (successRate < 0.0 || successRate > 1.0) {
            throw new IllegalArgumentException(
                "EvaluationStatistics: successRate must be within [0.0, 1.0] (was " + successRate + ")."
            );
        }
        if (failureRate < 0.0 || failureRate > 1.0) {
            throw new IllegalArgumentException(
                "EvaluationStatistics: failureRate must be within [0.0, 1.0] (was " + failureRate + ")."
            );
        }

        if (totalEvaluations == 0) {
            if (Math.abs(successRate) > RATE_TOLERANCE || Math.abs(failureRate) > RATE_TOLERANCE) {
                throw new IllegalArgumentException(
                    "EvaluationStatistics: successRate and failureRate must both be 0.0 " +
                    "when totalEvaluations == 0 (was successRate=" + successRate +
                    ", failureRate=" + failureRate + ")."
                );
            }
            if (Math.abs(averageDurationMillis) > RATE_TOLERANCE) {
                throw new IllegalArgumentException(
                    "EvaluationStatistics: averageDurationMillis must be 0.0 when " +
                    "totalEvaluations == 0 (was " + averageDurationMillis + ")."
                );
            }
        } else {
            double rateSum = successRate + failureRate;
            if (Math.abs(rateSum - 1.0) > RATE_TOLERANCE) {
                throw new IllegalArgumentException(
                    "EvaluationStatistics: successRate + failureRate must equal 1.0 " +
                    "when totalEvaluations > 0 (was " + rateSum + ")."
                );
            }
            if (averageDurationMillis < 0.0) {
                throw new IllegalArgumentException(
                    "EvaluationStatistics: averageDurationMillis must not be negative " +
                    "(was " + averageDurationMillis + ")."
                );
            }
        }

        this.totalEvaluations = totalEvaluations;
        this.successfulEvaluations = successfulEvaluations;
        this.compileErrorCount = compileErrorCount;
        this.runtimeErrorCount = runtimeErrorCount;
        this.timeoutCount = timeoutCount;
        this.successRate = successRate;
        this.failureRate = failureRate;
        this.averageDurationMillis = averageDurationMillis;
        this.aiRequestedCount = aiRequestedCount;
        this.aiAvailableCount = aiAvailableCount;
        this.aiUnavailableCount = aiUnavailableCount;
    }

    private static void requireNonNegative(int value, String fieldName) {
        if (value < 0) {
            throw new IllegalArgumentException(
                "EvaluationStatistics: " + fieldName + " must not be negative (was " + value + ")."
            );
        }
    }

    /**
     * Returns the canonical statistics instance representing zero
     * evaluations: every count is 0, both rates are 0.0, and
     * averageDurationMillis is 0.0. Provided as a convenience so callers
     * (e.g. a future analytics engine operating on an empty history)
     * don't need to repeat the eleven zero-valued arguments inline.
     *
     * @return an EvaluationStatistics instance representing an empty dataset
     */
    public static EvaluationStatistics empty() {
        return new EvaluationStatistics(0, 0, 0, 0, 0, 0.0, 0.0, 0.0, 0, 0, 0);
    }

    /** @return the total number of evaluations included in this snapshot. */
    public int getTotalEvaluations() {
        return totalEvaluations;
    }

    /** @return the count of evaluations with Result.Status.SUCCESS. */
    public int getSuccessfulEvaluations() {
        return successfulEvaluations;
    }

    /** @return the count of evaluations with Result.Status.COMPILE_ERROR. */
    public int getCompileErrorCount() {
        return compileErrorCount;
    }

    /** @return the count of evaluations with Result.Status.RUNTIME_ERROR. */
    public int getRuntimeErrorCount() {
        return runtimeErrorCount;
    }

    /** @return the count of evaluations with Result.Status.TIMEOUT. */
    public int getTimeoutCount() {
        return timeoutCount;
    }

    /** @return the fraction of evaluations that succeeded, in [0.0, 1.0]. */
    public double getSuccessRate() {
        return successRate;
    }

    /** @return the fraction of evaluations that did not succeed, in [0.0, 1.0]. */
    public double getFailureRate() {
        return failureRate;
    }

    /** @return the average duration, in milliseconds, of the included evaluations. */
    public double getAverageDurationMillis() {
        return averageDurationMillis;
    }

    /** @return the count of evaluations for which the AI was actually called
     *          (AiRequestStatus.AVAILABLE + AiRequestStatus.UNAVAILABLE). */
    public int getAiRequestedCount() {
        return aiRequestedCount;
    }

    /** @return the count of evaluations with AiRequestStatus.AVAILABLE. */
    public int getAiAvailableCount() {
        return aiAvailableCount;
    }

    /** @return the count of evaluations with AiRequestStatus.UNAVAILABLE. */
    public int getAiUnavailableCount() {
        return aiUnavailableCount;
    }

    @Override
    public String toString() {
        return "EvaluationStatistics{" +
                "totalEvaluations=" + totalEvaluations +
                ", successfulEvaluations=" + successfulEvaluations +
                ", compileErrorCount=" + compileErrorCount +
                ", runtimeErrorCount=" + runtimeErrorCount +
                ", timeoutCount=" + timeoutCount +
                ", successRate=" + successRate +
                ", failureRate=" + failureRate +
                ", averageDurationMillis=" + averageDurationMillis +
                ", aiRequestedCount=" + aiRequestedCount +
                ", aiAvailableCount=" + aiAvailableCount +
                ", aiUnavailableCount=" + aiUnavailableCount +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EvaluationStatistics)) return false;
        EvaluationStatistics that = (EvaluationStatistics) o;
        return totalEvaluations == that.totalEvaluations
                && successfulEvaluations == that.successfulEvaluations
                && compileErrorCount == that.compileErrorCount
                && runtimeErrorCount == that.runtimeErrorCount
                && timeoutCount == that.timeoutCount
                && Double.compare(that.successRate, successRate) == 0
                && Double.compare(that.failureRate, failureRate) == 0
                && Double.compare(that.averageDurationMillis, averageDurationMillis) == 0
                && aiRequestedCount == that.aiRequestedCount
                && aiAvailableCount == that.aiAvailableCount
                && aiUnavailableCount == that.aiUnavailableCount;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                totalEvaluations, successfulEvaluations, compileErrorCount,
                runtimeErrorCount, timeoutCount, successRate, failureRate,
                averageDurationMillis, aiRequestedCount, aiAvailableCount, aiUnavailableCount
        );
    }
}
