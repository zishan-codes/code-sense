package com.sce.analytics;

import java.lang.reflect.Method;

/**
 * EvaluationStatisticsTest
 *
 * TEMPORARY, standalone verification harness for EvaluationStatistics.
 * Not part of the production architecture — exists solely to prove the
 * data model's construction, validation, equality, and immutability
 * behave correctly. Should be deleted once Day 4.1 is confirmed working.
 *
 * @author Smart Code Evaluator Team
 */
public class EvaluationStatisticsTest {

    private static int passCount = 0;
    private static int failCount = 0;

    public static void main(String[] args) {
        System.out.println("[StatisticsTest] Starting EvaluationStatistics Day 4.1 verification...");

        testNormalConstructionAndGetters();
        testZeroEvaluationsViaConstructor();
        testZeroEvaluationsViaEmptyFactory();
        testNegativeCountRejected();
        testStatusSumMismatchRejected();
        testAiSumMismatchRejected();
        testAiRequestedExceedsTotalRejected();
        testRateOutOfRangeRejected();
        testRateSumMismatchRejected();
        testNonZeroDatasetRequiresNonZeroRateSumOrRejects();
        testEqualsAndHashCode();
        testToStringContainsUsefulInfo();
        testNoSetterMethodsExist();

        System.out.println();
        System.out.println("[StatisticsTest] " + passCount + " passed, " + failCount + " failed.");
        if (failCount == 0) {
            System.out.println("[StatisticsTest] ALL CHECKS PASSED.");
        } else {
            System.out.println("[StatisticsTest] SOME CHECKS FAILED.");
        }
    }

    private static void pass(String message) {
        passCount++;
        System.out.println("[StatisticsTest] PASS — " + message);
    }

    private static void fail(String message) {
        failCount++;
        System.err.println("[StatisticsTest] FAIL — " + message);
    }

    private static void testNormalConstructionAndGetters() {
        // 10 total: 6 success, 2 compile errors, 1 runtime error, 1 timeout.
        // AI requested for the 4 non-success cases: 3 available, 1 unavailable.
        EvaluationStatistics stats = new EvaluationStatistics(
                10, 6, 2, 1, 1,
                0.6, 0.4,
                250.5,
                4, 3, 1
        );

        boolean ok = stats.getTotalEvaluations() == 10
                && stats.getSuccessfulEvaluations() == 6
                && stats.getCompileErrorCount() == 2
                && stats.getRuntimeErrorCount() == 1
                && stats.getTimeoutCount() == 1
                && stats.getSuccessRate() == 0.6
                && stats.getFailureRate() == 0.4
                && stats.getAverageDurationMillis() == 250.5
                && stats.getAiRequestedCount() == 4
                && stats.getAiAvailableCount() == 3
                && stats.getAiUnavailableCount() == 1;

        if (ok) {
            pass("normal construction and all getters return the expected values.");
        } else {
            fail("normal construction — one or more getters returned an unexpected value: " + stats);
        }
    }

    private static void testZeroEvaluationsViaConstructor() {
        try {
            EvaluationStatistics stats = new EvaluationStatistics(
                    0, 0, 0, 0, 0, 0.0, 0.0, 0.0, 0, 0, 0
            );
            if (stats.getTotalEvaluations() == 0 && stats.getSuccessRate() == 0.0
                    && stats.getFailureRate() == 0.0 && stats.getAverageDurationMillis() == 0.0) {
                pass("zero-evaluation statistics can be constructed directly and are valid.");
            } else {
                fail("zero-evaluation construction produced unexpected field values: " + stats);
            }
        } catch (IllegalArgumentException e) {
            fail("zero-evaluation construction was rejected but should be valid: " + e.getMessage());
        }
    }

    private static void testZeroEvaluationsViaEmptyFactory() {
        EvaluationStatistics empty = EvaluationStatistics.empty();
        EvaluationStatistics manual = new EvaluationStatistics(
                0, 0, 0, 0, 0, 0.0, 0.0, 0.0, 0, 0, 0
        );
        if (empty.equals(manual)) {
            pass("empty() factory returns a statistics object equal to the manually-built zero case.");
        } else {
            fail("empty() factory result does not equal the manually-built zero case.");
        }
    }

    private static void testNegativeCountRejected() {
        try {
            new EvaluationStatistics(-1, 0, 0, 0, 0, 0.0, 0.0, 0.0, 0, 0, 0);
            fail("negative totalEvaluations was NOT rejected.");
        } catch (IllegalArgumentException e) {
            pass("negative totalEvaluations correctly rejected: " + e.getMessage());
        }
    }

    private static void testStatusSumMismatchRejected() {
        try {
            // 5 total claimed, but 6+1+0+0 = 7 actually supplied.
            new EvaluationStatistics(5, 6, 1, 0, 0, 1.0, 0.0, 100.0, 0, 0, 0);
            fail("status-count sum mismatch was NOT rejected.");
        } catch (IllegalArgumentException e) {
            pass("status-count sum mismatch correctly rejected: " + e.getMessage());
        }
    }

    private static void testAiSumMismatchRejected() {
        try {
            // aiRequestedCount=4 claimed, but available+unavailable = 2+1 = 3.
            new EvaluationStatistics(10, 10, 0, 0, 0, 1.0, 0.0, 100.0, 4, 2, 1);
            fail("AI available+unavailable mismatch with aiRequestedCount was NOT rejected.");
        } catch (IllegalArgumentException e) {
            pass("AI count mismatch correctly rejected: " + e.getMessage());
        }
    }

    private static void testAiRequestedExceedsTotalRejected() {
        try {
            // aiRequestedCount=11 exceeds totalEvaluations=10.
            new EvaluationStatistics(10, 10, 0, 0, 0, 1.0, 0.0, 100.0, 11, 6, 5);
            fail("aiRequestedCount exceeding totalEvaluations was NOT rejected.");
        } catch (IllegalArgumentException e) {
            pass("aiRequestedCount exceeding totalEvaluations correctly rejected: " + e.getMessage());
        }
    }

    private static void testRateOutOfRangeRejected() {
        try {
            new EvaluationStatistics(10, 10, 0, 0, 0, 1.5, -0.5, 100.0, 0, 0, 0);
            fail("out-of-range successRate/failureRate was NOT rejected.");
        } catch (IllegalArgumentException e) {
            pass("out-of-range rate correctly rejected: " + e.getMessage());
        }
    }

    private static void testRateSumMismatchRejected() {
        try {
            // successRate + failureRate = 0.9, not 1.0, for a non-empty dataset.
            new EvaluationStatistics(10, 10, 0, 0, 0, 0.5, 0.4, 100.0, 0, 0, 0);
            fail("successRate + failureRate != 1.0 for a non-empty dataset was NOT rejected.");
        } catch (IllegalArgumentException e) {
            pass("rate-sum mismatch for non-empty dataset correctly rejected: " + e.getMessage());
        }
    }

    private static void testNonZeroDatasetRequiresNonZeroRateSumOrRejects() {
        try {
            // totalEvaluations=0 but rates are non-zero — must be rejected.
            new EvaluationStatistics(0, 0, 0, 0, 0, 0.5, 0.5, 0.0, 0, 0, 0);
            fail("non-zero rates on a zero-evaluation dataset were NOT rejected.");
        } catch (IllegalArgumentException e) {
            pass("non-zero rates on a zero-evaluation dataset correctly rejected: " + e.getMessage());
        }
    }

    private static void testEqualsAndHashCode() {
        EvaluationStatistics a = new EvaluationStatistics(10, 6, 2, 1, 1, 0.6, 0.4, 250.5, 4, 3, 1);
        EvaluationStatistics b = new EvaluationStatistics(10, 6, 2, 1, 1, 0.6, 0.4, 250.5, 4, 3, 1);
        EvaluationStatistics c = new EvaluationStatistics(10, 7, 1, 1, 1, 0.7, 0.3, 250.5, 4, 3, 1);

        boolean equalPairMatches = a.equals(b) && b.equals(a);
        boolean hashMatches = a.hashCode() == b.hashCode();
        boolean differentObjectsNotEqual = !a.equals(c);
        boolean notEqualToNull = !a.equals(null);
        boolean notEqualToOtherType = !a.equals("not a statistics object");

        if (equalPairMatches && hashMatches && differentObjectsNotEqual && notEqualToNull && notEqualToOtherType) {
            pass("equals()/hashCode() behave consistently for equal, unequal, null, and cross-type comparisons.");
        } else {
            fail("equals()/hashCode() consistency check failed. equalPairMatches=" + equalPairMatches
                    + ", hashMatches=" + hashMatches + ", differentObjectsNotEqual=" + differentObjectsNotEqual
                    + ", notEqualToNull=" + notEqualToNull + ", notEqualToOtherType=" + notEqualToOtherType);
        }
    }

    private static void testToStringContainsUsefulInfo() {
        EvaluationStatistics stats = new EvaluationStatistics(10, 6, 2, 1, 1, 0.6, 0.4, 250.5, 4, 3, 1);
        String s = stats.toString();

        boolean ok = s.contains("totalEvaluations=10")
                && s.contains("successfulEvaluations=6")
                && s.contains("compileErrorCount=2")
                && s.contains("runtimeErrorCount=1")
                && s.contains("timeoutCount=1")
                && s.contains("successRate=0.6")
                && s.contains("failureRate=0.4")
                && s.contains("aiRequestedCount=4")
                && s.contains("aiAvailableCount=3")
                && s.contains("aiUnavailableCount=1");

        if (ok) {
            pass("toString() contains all expected field values: " + s);
        } else {
            fail("toString() is missing expected content: " + s);
        }
    }

    /**
     * Reflection-based structural immutability check: confirms no public
     * method whose name starts with "set" exists on EvaluationStatistics.
     * This does not replace the fact that every field is already private
     * and final (verified by inspection of the source above) — it is an
     * additional, automated guard against a future accidental setter
     * being introduced.
     */
    private static void testNoSetterMethodsExist() {
        Method[] methods = EvaluationStatistics.class.getMethods();
        for (Method m : methods) {
            if (m.getName().startsWith("set")) {
                fail("found an unexpected setter-like method: " + m.getName());
                return;
            }
        }
        pass("no setter-like public methods exist on EvaluationStatistics (structural immutability confirmed).");
    }
}
