package com.sce.analytics;

import com.sce.core.EvaluationRecord;
import com.sce.core.Result;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * EvaluationAnalyticsTest
 *
 * TEMPORARY, standalone verification harness for EvaluationAnalytics.
 * Not part of the production architecture — exists solely to prove the
 * calculation layer aggregates a List of EvaluationRecord objects
 * correctly, in isolation from SQLite/EvaluationRepository. Should be
 * deleted once Day 4.2 is confirmed working.
 *
 * @author Smart Code Evaluator Team
 */
public class EvaluationAnalyticsTest {

    private static int passCount = 0;
    private static int failCount = 0;

    public static void main(String[] args) {
        System.out.println("[AnalyticsTest] Starting EvaluationAnalytics Day 4.2 verification...");

        testNormalMixedDataset();
        testEmptyList();
        testNullInputRejected();
        testNullElementRejected();
        testSuccessRateExact();
        testFailureRateExact();
        testAverageDurationFractional();
        testAiCountsExcludeNotRequested();
        testInputListNotModified();
        testRepeatedCalculationIsDeterministic();

        System.out.println();
        System.out.println("[AnalyticsTest] " + passCount + " passed, " + failCount + " failed.");
        if (failCount == 0) {
            System.out.println("[AnalyticsTest] ALL CHECKS PASSED.");
        } else {
            System.out.println("[AnalyticsTest] SOME CHECKS FAILED.");
        }
    }

    private static void pass(String message) {
        passCount++;
        System.out.println("[AnalyticsTest] PASS — " + message);
    }

    private static void fail(String message) {
        failCount++;
        System.err.println("[AnalyticsTest] FAIL — " + message);
    }

    private static EvaluationRecord makeRecord(String id,
                                                Result.Status status,
                                                long durationMillis,
                                                EvaluationRecord.AiRequestStatus aiRequestStatus) {
        String aiHint = aiRequestStatus == EvaluationRecord.AiRequestStatus.AVAILABLE ? "some hint" : "";
        String aiFix = aiRequestStatus == EvaluationRecord.AiRequestStatus.AVAILABLE ? "some fix" : "";
        int exitCode = status == Result.Status.SUCCESS ? 0 : 1;

        return new EvaluationRecord(
                id,
                Instant.now(),
                "TestClass",
                "public class TestClass {}",
                status,
                exitCode,
                "",
                "",
                aiHint,
                aiFix,
                aiRequestStatus,
                durationMillis
        );
    }

    /**
     * Builds a realistic mixed dataset used by several tests:
     * 10 records total —
     *   4 SUCCESS      (durations: 100, 200, 300, 400)   AI: NOT_REQUESTED x4
     *   2 COMPILE_ERROR (durations: 50, 150)              AI: AVAILABLE, UNAVAILABLE
     *   2 RUNTIME_ERROR (durations: 500, 600)              AI: AVAILABLE, AVAILABLE
     *   2 TIMEOUT        (durations: 3000, 3000)            AI: UNAVAILABLE, UNAVAILABLE
     *
     * Totals: successfulEvaluations=4, compileErrorCount=2,
     * runtimeErrorCount=2, timeoutCount=2.
     * Duration sum = 100+200+300+400+50+150+500+600+3000+3000 = 8300
     * Average = 8300 / 10 = 830.0
     * successRate = 4/10 = 0.4
     * failureRate = 6/10 = 0.6
     * aiAvailableCount = 3 (1 compile + 2 runtime)
     * aiUnavailableCount = 3 (1 compile + 2 timeout)
     * aiRequestedCount = 6
     */
    private static List<EvaluationRecord> buildMixedDataset() {
        List<EvaluationRecord> records = new ArrayList<>();
        records.add(makeRecord("S1", Result.Status.SUCCESS, 100, EvaluationRecord.AiRequestStatus.NOT_REQUESTED));
        records.add(makeRecord("S2", Result.Status.SUCCESS, 200, EvaluationRecord.AiRequestStatus.NOT_REQUESTED));
        records.add(makeRecord("S3", Result.Status.SUCCESS, 300, EvaluationRecord.AiRequestStatus.NOT_REQUESTED));
        records.add(makeRecord("S4", Result.Status.SUCCESS, 400, EvaluationRecord.AiRequestStatus.NOT_REQUESTED));
        records.add(makeRecord("C1", Result.Status.COMPILE_ERROR, 50, EvaluationRecord.AiRequestStatus.AVAILABLE));
        records.add(makeRecord("C2", Result.Status.COMPILE_ERROR, 150, EvaluationRecord.AiRequestStatus.UNAVAILABLE));
        records.add(makeRecord("R1", Result.Status.RUNTIME_ERROR, 500, EvaluationRecord.AiRequestStatus.AVAILABLE));
        records.add(makeRecord("R2", Result.Status.RUNTIME_ERROR, 600, EvaluationRecord.AiRequestStatus.AVAILABLE));
        records.add(makeRecord("T1", Result.Status.TIMEOUT, 3000, EvaluationRecord.AiRequestStatus.UNAVAILABLE));
        records.add(makeRecord("T2", Result.Status.TIMEOUT, 3000, EvaluationRecord.AiRequestStatus.UNAVAILABLE));
        return records;
    }

    private static void testNormalMixedDataset() {
        EvaluationAnalytics analytics = new EvaluationAnalytics();
        List<EvaluationRecord> records = buildMixedDataset();
        EvaluationStatistics stats = analytics.calculateStatistics(records);

        boolean ok = stats.getTotalEvaluations() == 10
                && stats.getSuccessfulEvaluations() == 4
                && stats.getCompileErrorCount() == 2
                && stats.getRuntimeErrorCount() == 2
                && stats.getTimeoutCount() == 2
                && Math.abs(stats.getSuccessRate() - 0.4) < 1e-9
                && Math.abs(stats.getFailureRate() - 0.6) < 1e-9
                && Math.abs(stats.getAverageDurationMillis() - 830.0) < 1e-9
                && stats.getAiRequestedCount() == 6
                && stats.getAiAvailableCount() == 3
                && stats.getAiUnavailableCount() == 3;

        if (ok) {
            pass("normal mixed dataset (10 records) produces the expected EvaluationStatistics: " + stats);
        } else {
            fail("normal mixed dataset produced unexpected statistics: " + stats);
        }
    }

    private static void testEmptyList() {
        EvaluationAnalytics analytics = new EvaluationAnalytics();
        EvaluationStatistics stats = analytics.calculateStatistics(new ArrayList<>());

        boolean ok = stats.getTotalEvaluations() == 0
                && stats.getSuccessfulEvaluations() == 0
                && stats.getCompileErrorCount() == 0
                && stats.getRuntimeErrorCount() == 0
                && stats.getTimeoutCount() == 0
                && stats.getSuccessRate() == 0.0
                && stats.getFailureRate() == 0.0
                && stats.getAverageDurationMillis() == 0.0
                && stats.getAiRequestedCount() == 0
                && stats.getAiAvailableCount() == 0
                && stats.getAiUnavailableCount() == 0;

        if (ok) {
            pass("empty list produces a valid all-zero EvaluationStatistics: " + stats);
        } else {
            fail("empty list did not produce all-zero statistics: " + stats);
        }
    }

    private static void testNullInputRejected() {
        EvaluationAnalytics analytics = new EvaluationAnalytics();
        try {
            analytics.calculateStatistics(null);
            fail("null input was NOT rejected.");
        } catch (IllegalArgumentException e) {
            pass("null input correctly rejected with IllegalArgumentException: " + e.getMessage());
        }
    }

    private static void testNullElementRejected() {
        EvaluationAnalytics analytics = new EvaluationAnalytics();
        List<EvaluationRecord> records = new ArrayList<>();
        records.add(makeRecord("OK1", Result.Status.SUCCESS, 100, EvaluationRecord.AiRequestStatus.NOT_REQUESTED));
        records.add(null);

        try {
            analytics.calculateStatistics(records);
            fail("a null element within the list was NOT rejected.");
        } catch (IllegalArgumentException e) {
            pass("null element within the list correctly rejected: " + e.getMessage());
        }
    }

    private static void testSuccessRateExact() {
        EvaluationAnalytics analytics = new EvaluationAnalytics();
        List<EvaluationRecord> records = new ArrayList<>();
        records.add(makeRecord("A", Result.Status.SUCCESS, 10, EvaluationRecord.AiRequestStatus.NOT_REQUESTED));
        records.add(makeRecord("B", Result.Status.SUCCESS, 10, EvaluationRecord.AiRequestStatus.NOT_REQUESTED));
        records.add(makeRecord("C", Result.Status.RUNTIME_ERROR, 10, EvaluationRecord.AiRequestStatus.AVAILABLE));

        EvaluationStatistics stats = analytics.calculateStatistics(records);
        double expected = 2.0 / 3.0;

        if (Math.abs(stats.getSuccessRate() - expected) < 1e-9) {
            pass("successRate exactly matches expected 2/3 = " + expected + " (actual " + stats.getSuccessRate() + ")");
        } else {
            fail("successRate mismatch — expected " + expected + " but got " + stats.getSuccessRate());
        }
    }

    private static void testFailureRateExact() {
        EvaluationAnalytics analytics = new EvaluationAnalytics();
        List<EvaluationRecord> records = new ArrayList<>();
        records.add(makeRecord("A", Result.Status.SUCCESS, 10, EvaluationRecord.AiRequestStatus.NOT_REQUESTED));
        records.add(makeRecord("B", Result.Status.COMPILE_ERROR, 10, EvaluationRecord.AiRequestStatus.AVAILABLE));
        records.add(makeRecord("C", Result.Status.RUNTIME_ERROR, 10, EvaluationRecord.AiRequestStatus.AVAILABLE));
        records.add(makeRecord("D", Result.Status.TIMEOUT, 10, EvaluationRecord.AiRequestStatus.UNAVAILABLE));

        EvaluationStatistics stats = analytics.calculateStatistics(records);
        double expected = 3.0 / 4.0;

        if (Math.abs(stats.getFailureRate() - expected) < 1e-9) {
            pass("failureRate exactly matches expected 3/4 = " + expected + " (actual " + stats.getFailureRate() + ")");
        } else {
            fail("failureRate mismatch — expected " + expected + " but got " + stats.getFailureRate());
        }
    }

    private static void testAverageDurationFractional() {
        EvaluationAnalytics analytics = new EvaluationAnalytics();
        List<EvaluationRecord> records = new ArrayList<>();
        records.add(makeRecord("A", Result.Status.SUCCESS, 100, EvaluationRecord.AiRequestStatus.NOT_REQUESTED));
        records.add(makeRecord("B", Result.Status.SUCCESS, 200, EvaluationRecord.AiRequestStatus.NOT_REQUESTED));
        records.add(makeRecord("C", Result.Status.SUCCESS, 300, EvaluationRecord.AiRequestStatus.NOT_REQUESTED));

        EvaluationStatistics stats = analytics.calculateStatistics(records);
        double expected = 200.0; // (100+200+300)/3 = 200.0 exactly, still verifies double arithmetic path

        List<EvaluationRecord> fractionalRecords = new ArrayList<>();
        fractionalRecords.add(makeRecord("X", Result.Status.SUCCESS, 100, EvaluationRecord.AiRequestStatus.NOT_REQUESTED));
        fractionalRecords.add(makeRecord("Y", Result.Status.SUCCESS, 200, EvaluationRecord.AiRequestStatus.NOT_REQUESTED));
        fractionalRecords.add(makeRecord("Z", Result.Status.SUCCESS, 200, EvaluationRecord.AiRequestStatus.NOT_REQUESTED));
        EvaluationStatistics fractionalStats = analytics.calculateStatistics(fractionalRecords);
        double expectedFractional = 500.0 / 3.0; // 166.666...

        boolean ok = Math.abs(stats.getAverageDurationMillis() - expected) < 1e-9
                && Math.abs(fractionalStats.getAverageDurationMillis() - expectedFractional) < 1e-9;

        if (ok) {
            pass("averageDurationMillis correctly computed, including a fractional (non-integer) case: "
                    + fractionalStats.getAverageDurationMillis());
        } else {
            fail("averageDurationMillis incorrect — integer case=" + stats.getAverageDurationMillis()
                    + ", fractional case=" + fractionalStats.getAverageDurationMillis()
                    + " (expected fractional=" + expectedFractional + ")");
        }
    }

    private static void testAiCountsExcludeNotRequested() {
        EvaluationAnalytics analytics = new EvaluationAnalytics();
        List<EvaluationRecord> records = new ArrayList<>();
        records.add(makeRecord("A", Result.Status.SUCCESS, 10, EvaluationRecord.AiRequestStatus.NOT_REQUESTED));
        records.add(makeRecord("B", Result.Status.SUCCESS, 10, EvaluationRecord.AiRequestStatus.NOT_REQUESTED));
        records.add(makeRecord("C", Result.Status.COMPILE_ERROR, 10, EvaluationRecord.AiRequestStatus.AVAILABLE));
        records.add(makeRecord("D", Result.Status.RUNTIME_ERROR, 10, EvaluationRecord.AiRequestStatus.UNAVAILABLE));

        EvaluationStatistics stats = analytics.calculateStatistics(records);

        boolean ok = stats.getAiAvailableCount() == 1
                && stats.getAiUnavailableCount() == 1
                && stats.getAiRequestedCount() == 2; // NOT_REQUESTED x2 excluded

        if (ok) {
            pass("AI counts correctly exclude NOT_REQUESTED: aiRequestedCount=" + stats.getAiRequestedCount()
                    + " (available=" + stats.getAiAvailableCount() + ", unavailable=" + stats.getAiUnavailableCount() + ")");
        } else {
            fail("AI counts incorrect — " + stats);
        }
    }

    private static void testInputListNotModified() {
        EvaluationAnalytics analytics = new EvaluationAnalytics();
        List<EvaluationRecord> records = buildMixedDataset();
        List<EvaluationRecord> snapshot = new ArrayList<>(records);

        analytics.calculateStatistics(records);

        boolean unchanged = records.size() == snapshot.size() && records.equals(snapshot);

        if (unchanged) {
            pass("input list is not modified by calculateStatistics() (size and contents unchanged).");
        } else {
            fail("input list WAS modified by calculateStatistics(). Before size=" + snapshot.size()
                    + ", after size=" + records.size());
        }
    }

    private static void testRepeatedCalculationIsDeterministic() {
        EvaluationAnalytics analytics = new EvaluationAnalytics();
        List<EvaluationRecord> records = Collections.unmodifiableList(buildMixedDataset());

        EvaluationStatistics first = analytics.calculateStatistics(records);
        EvaluationStatistics second = analytics.calculateStatistics(records);
        EvaluationStatistics third = analytics.calculateStatistics(records);

        boolean allEqual = first.equals(second) && second.equals(third);

        if (allEqual) {
            pass("repeated calculateStatistics() calls with the same input produce identical, equal results.");
        } else {
            fail("repeated calculateStatistics() calls produced different results. first=" + first
                    + ", second=" + second + ", third=" + third);
        }
    }
}
