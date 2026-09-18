package com.sce.analytics;

import com.sce.core.EvaluationRecord;
import com.sce.core.Result;

import java.util.List;

/**
 * EvaluationAnalytics
 *
 * Stateless calculation layer that converts a List of EvaluationRecord
 * objects into a single, immutable EvaluationStatistics snapshot. This
 * class performs the aggregation itself; EvaluationStatistics (Day 4.1)
 * is a pure result/value object that performs no calculation of its own.
 *
 * DEPENDENCIES: this class depends only on java.util.List,
 * com.sce.core.EvaluationRecord, com.sce.core.Result (for the Status
 * enum), and com.sce.analytics.EvaluationStatistics. It has no
 * dependency on DatabaseManager, EvaluationRepository, Swing, any
 * REST/API type, AIConnector, or ExecutionEngine — records are supplied
 * to it directly by the caller (e.g. a future consumer of
 * EvaluationHistoryService), and this class never reaches into
 * persistence itself.
 *
 * STATELESSNESS: this class holds no fields at all. It does not store
 * the supplied list, does not mutate it, and does not modify any
 * EvaluationRecord within it. Calling calculateStatistics() repeatedly
 * with the same input always produces an equal EvaluationStatistics
 * result — there is no caching, no memoization, and no hidden state
 * that could cause two calls with identical input to diverge.
 *
 * @author Smart Code Evaluator Team
 */
public final class EvaluationAnalytics {

    /**
     * Calculates aggregated statistics from the given list of evaluation
     * records. The input list itself is never modified, and no reference
     * to it (or to any element within it) is retained after this method
     * returns.
     *
     * @param evaluations the records to aggregate; must not be null, but
     *                     may be empty, in which case
     *                     EvaluationStatistics.empty() is returned
     * @return a fully populated, immutable EvaluationStatistics snapshot;
     *          never null
     * @throws IllegalArgumentException if evaluations is null, or if any
     *                                    element of evaluations is null,
     *                                    or if any element has a status
     *                                    value that does not match one of
     *                                    the four known Result.Status
     *                                    constants (a defensive guard
     *                                    against a future enum-drift bug
     *                                    rather than a case expected to
     *                                    occur in practice, since
     *                                    EvaluationRecord's own
     *                                    constructor already guarantees a
     *                                    non-null status)
     */
    public EvaluationStatistics calculateStatistics(List<EvaluationRecord> evaluations) {
        if (evaluations == null) {
            throw new IllegalArgumentException(
                "EvaluationAnalytics.calculateStatistics: evaluations list must not be null."
            );
        }

        if (evaluations.isEmpty()) {
            return EvaluationStatistics.empty();
        }

        int totalEvaluations = 0;
        int successfulEvaluations = 0;
        int compileErrorCount = 0;
        int runtimeErrorCount = 0;
        int timeoutCount = 0;
        int aiAvailableCount = 0;
        int aiUnavailableCount = 0;
        long durationSumMillis = 0L;

        for (EvaluationRecord record : evaluations) {
            if (record == null) {
                throw new IllegalArgumentException(
                    "EvaluationAnalytics.calculateStatistics: evaluations list must not contain " +
                    "null elements (found at index " + totalEvaluations + ")."
                );
            }

            totalEvaluations++;

            Result.Status status = record.getStatus();
            switch (status) {
                case SUCCESS:
                    successfulEvaluations++;
                    break;
                case COMPILE_ERROR:
                    compileErrorCount++;
                    break;
                case RUNTIME_ERROR:
                    runtimeErrorCount++;
                    break;
                case TIMEOUT:
                    timeoutCount++;
                    break;
                default:
                    // Defensive guard only: EvaluationRecord's constructor
                    // already guarantees a non-null Result.Status, and
                    // Result.Status currently defines exactly these four
                    // constants. This branch exists so that if a future
                    // status value were ever added without updating this
                    // switch, the failure is loud and explicit rather than
                    // silently producing an EvaluationStatistics object
                    // whose status counts no longer partition the total —
                    // which EvaluationStatistics's own constructor would
                    // then reject anyway, but with a far less specific
                    // error message than this one.
                    throw new IllegalArgumentException(
                        "EvaluationAnalytics.calculateStatistics: encountered an unrecognized " +
                        "Result.Status value '" + status + "' on evaluation_id='" +
                        record.getEvaluationId() + "'. This analytics layer does not know how " +
                        "to classify this status."
                    );
            }

            EvaluationRecord.AiRequestStatus aiRequestStatus = record.getAiRequestStatus();
            if (aiRequestStatus == EvaluationRecord.AiRequestStatus.AVAILABLE) {
                aiAvailableCount++;
            } else if (aiRequestStatus == EvaluationRecord.AiRequestStatus.UNAVAILABLE) {
                aiUnavailableCount++;
            }
            // AiRequestStatus.NOT_REQUESTED is intentionally not counted
            // toward aiRequestedCount, aiAvailableCount, or
            // aiUnavailableCount — per the required semantics, only
            // evaluations where the AI was actually called contribute to
            // these counts.

            durationSumMillis += record.getDurationMillis();
        }

        int aiRequestedCount = aiAvailableCount + aiUnavailableCount;

        double successRate = (double) successfulEvaluations / (double) totalEvaluations;
        int failureCount = compileErrorCount + runtimeErrorCount + timeoutCount;
        double failureRate = (double) failureCount / (double) totalEvaluations;

        double averageDurationMillis = (double) durationSumMillis / (double) totalEvaluations;

        return new EvaluationStatistics(
                totalEvaluations,
                successfulEvaluations,
                compileErrorCount,
                runtimeErrorCount,
                timeoutCount,
                successRate,
                failureRate,
                averageDurationMillis,
                aiRequestedCount,
                aiAvailableCount,
                aiUnavailableCount
        );
    }
}
