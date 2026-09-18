package com.sce.history;

import com.sce.analytics.EvaluationStatistics;
import com.sce.core.EvaluationRecord;
import com.sce.core.Result;
import com.sce.database.DatabaseManager;
import com.sce.database.EvaluationRepository;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * EvaluationHistoryServiceTest
 *
 * TEMPORARY, standalone verification harness for EvaluationHistoryService,
 * covering both the pre-existing history retrieval behavior (Day 3.2/3.3)
 * and the new Day 4.3 analytics integration (getStatistics()). Not part
 * of the production architecture — should be deleted once Day 4.3 is
 * confirmed working.
 *
 * Uses a freshly unique evaluation_id prefix per run (timestamp-suffixed)
 * so no existing production row is touched or duplicated, and never
 * deletes or resets the underlying table.
 *
 * @author Smart Code Evaluator Team
 */
public class EvaluationHistoryServiceTest {

    private static final String TEST_ID_PREFIX = "DAY4-3-TEST-";

    private static int passCount = 0;
    private static int failCount = 0;

    public static void main(String[] args) {
        System.out.println("[HistoryServiceTest] Starting EvaluationHistoryService Day 4.3 verification...");

        String runPrefix = TEST_ID_PREFIX + System.currentTimeMillis() + "-";

        try {
            DatabaseManager.initializeDatabase();
            System.out.println("[HistoryServiceTest] Database initialized (schema ensured).");

            EvaluationRepository repository = new EvaluationRepository();
            EvaluationHistoryService historyService = new EvaluationHistoryService(repository);

            // ---- Pre-existing (Day 3.2/3.3) behavior — regression checks ----
            String singleId = runPrefix + "SINGLE";
            EvaluationRecord seeded = new EvaluationRecord(
                    singleId,
                    Instant.now(),
                    "HistoryDemo",
                    "public class HistoryDemo {\n"
                            + "    public static void main(String[] args) {\n"
                            + "        System.out.println(\"ok\");\n"
                            + "    }\n"
                            + "}\n",
                    Result.Status.SUCCESS,
                    0,
                    "ok\n",
                    "",
                    "",
                    "",
                    EvaluationRecord.AiRequestStatus.NOT_REQUESTED,
                    120L
            );
            repository.save(seeded);
            System.out.println("[HistoryServiceTest] Seeded single regression-test record: " + singleId);

            testGetRecentEvaluations(historyService, singleId);
            testRecentEvaluationsListIsUnmodifiable(historyService);
            testGetEvaluationByIdFound(historyService, singleId, seeded);
            testGetEvaluationByIdMissing(historyService);
            testInvalidLimitPropagates(historyService);
            testDatabaseFailureIsWrapped();

            // ---- Day 4.3 analytics integration ----
            List<EvaluationRecord> statsDataset = seedStatisticsDataset(repository, runPrefix);

            testStatisticsFromPersistedRecords(historyService, statsDataset);
            testStatisticsRespectsLimit(historyService, repository, runPrefix);
            testStatisticsEmptyHistory();
            testStatisticsInvalidLimitRejected(historyService);
            testStatisticsExceptionWrapping();
            testStatisticsDelegationCorrectness(historyService, statsDataset);
            testStatisticsDoesNotMutateInput(historyService, statsDataset.size());
            testStatisticsRepeatedCallsDeterministic(historyService, statsDataset.size());

            System.out.println();
            System.out.println("[HistoryServiceTest] " + passCount + " passed, " + failCount + " failed.");
            if (failCount == 0) {
                System.out.println("[HistoryServiceTest] ALL CHECKS PASSED.");
            } else {
                System.out.println("[HistoryServiceTest] SOME CHECKS FAILED.");
            }

        } catch (SQLException e) {
            failCount++;
            System.err.println("[HistoryServiceTest] FAIL — unexpected SQLException during test setup:");
            e.printStackTrace();
        } catch (EvaluationHistoryException e) {
            failCount++;
            System.err.println("[HistoryServiceTest] FAIL — unexpected EvaluationHistoryException:");
            e.printStackTrace();
        }
    }

    /**
     * Sleeps for a few milliseconds so consecutively-saved test records
     * receive distinguishable Instant.now() timestamps, guaranteeing a
     * deterministic ORDER BY timestamp DESC ordering for the limit test.
     * Any InterruptedException is handled locally (interrupt status
     * restored) rather than propagated, since this is test-timing
     * housekeeping, not a condition the caller needs to react to.
     */
    private static void sleepBriefly() {
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void pass(String message) {
        passCount++;
        System.out.println("[HistoryServiceTest] PASS — " + message);
    }

    private static void fail(String message) {
        failCount++;
        System.err.println("[HistoryServiceTest] FAIL — " + message);
    }

    // =====================================================================
    // Pre-existing (Day 3.2 / 3.3) regression checks
    // =====================================================================

    private static void testGetRecentEvaluations(EvaluationHistoryService service,
                                                  String testEvaluationId) throws EvaluationHistoryException {
        List<EvaluationRecord> recent = service.getRecentEvaluations(5);

        if (recent.isEmpty()) {
            fail("getRecentEvaluations(5) returned an empty list.");
            return;
        }

        boolean containsTestRecord = recent.stream()
                .anyMatch(r -> r.getEvaluationId().equals(testEvaluationId));

        if (containsTestRecord) {
            pass("getRecentEvaluations(5) returned " + recent.size() + " record(s), including the seeded record.");
        } else {
            fail("getRecentEvaluations(5) did not include the seeded record.");
        }
    }

    private static void testRecentEvaluationsListIsUnmodifiable(EvaluationHistoryService service)
            throws EvaluationHistoryException {
        List<EvaluationRecord> recent = service.getRecentEvaluations(1);
        try {
            recent.add(null);
            fail("getRecentEvaluations() returned a modifiable list.");
        } catch (UnsupportedOperationException expected) {
            pass("getRecentEvaluations() returns an unmodifiable list.");
        }
    }

    private static void testGetEvaluationByIdFound(EvaluationHistoryService service,
                                                     String testEvaluationId,
                                                     EvaluationRecord seeded) throws EvaluationHistoryException {
        EvaluationRecord fetched = service.getEvaluationById(testEvaluationId);

        if (fetched == null) {
            fail("getEvaluationById() returned null for a known ID.");
            return;
        }

        boolean matches = fetched.getEvaluationId().equals(seeded.getEvaluationId())
                && fetched.getStatus() == seeded.getStatus();

        if (matches) {
            pass("getEvaluationById() returned the correct record.");
        } else {
            fail("getEvaluationById() returned a mismatched record.");
        }
    }

    private static void testGetEvaluationByIdMissing(EvaluationHistoryService service) throws EvaluationHistoryException {
        EvaluationRecord missing = service.getEvaluationById("DAY4-3-TEST-DOES-NOT-EXIST-0000");

        if (missing == null) {
            pass("getEvaluationById() correctly returned null for an unknown ID.");
        } else {
            fail("getEvaluationById() returned a non-null record for an unknown ID.");
        }
    }

    private static void testInvalidLimitPropagates(EvaluationHistoryService service) {
        try {
            service.getRecentEvaluations(0);
            fail("getRecentEvaluations(0) should have thrown IllegalArgumentException.");
        } catch (IllegalArgumentException e) {
            pass("getRecentEvaluations(0) correctly rejected with: " + e.getMessage());
        } catch (EvaluationHistoryException e) {
            fail("getRecentEvaluations(0) threw EvaluationHistoryException instead of IllegalArgumentException.");
        }
    }

    private static void testDatabaseFailureIsWrapped() {
        EvaluationHistoryService failingService = new EvaluationHistoryService(new AlwaysFailingRepository());

        boolean recentFailedCorrectly = false;
        try {
            failingService.getRecentEvaluations(5);
        } catch (EvaluationHistoryException e) {
            recentFailedCorrectly = e.getCause() instanceof SQLException;
        } catch (Exception unexpected) {
            fail("getRecentEvaluations() on failure threw the wrong exception type: " + unexpected);
            return;
        }

        boolean byIdFailedCorrectly = false;
        try {
            failingService.getEvaluationById("any-id");
        } catch (EvaluationHistoryException e) {
            byIdFailedCorrectly = e.getCause() instanceof SQLException;
        } catch (Exception unexpected) {
            fail("getEvaluationById() on failure threw the wrong exception type: " + unexpected);
            return;
        }

        if (recentFailedCorrectly && byIdFailedCorrectly) {
            pass("persistence failures correctly wrapped as EvaluationHistoryException for "
                    + "getRecentEvaluations() and getEvaluationById().");
        } else {
            fail("persistence failure wrapping incorrect for getRecentEvaluations/getEvaluationById.");
        }
    }

    // =====================================================================
    // Day 4.3 — analytics integration checks
    // =====================================================================

    /**
     * Seeds a known, hand-calculable dataset of 10 records:
     *   4 SUCCESS (durations 100,200,300,400)        AI: NOT_REQUESTED x4
     *   2 COMPILE_ERROR (durations 50,150)             AI: AVAILABLE, UNAVAILABLE
     *   2 RUNTIME_ERROR (durations 500,600)             AI: AVAILABLE, AVAILABLE
     *   2 TIMEOUT (durations 3000,3000)                  AI: UNAVAILABLE, UNAVAILABLE
     * Expected aggregates: total=10, success=4, compile=2, runtime=2, timeout=2,
     * successRate=0.4, failureRate=0.6, avgDuration=830.0,
     * aiRequestedCount=6, aiAvailableCount=3, aiUnavailableCount=3.
     */
    private static List<EvaluationRecord> seedStatisticsDataset(EvaluationRepository repository,
                                                                  String runPrefix) throws SQLException {
        List<EvaluationRecord> records = new ArrayList<>();
        records.add(makeRecord(runPrefix + "S1", Result.Status.SUCCESS, 100, EvaluationRecord.AiRequestStatus.NOT_REQUESTED));
        records.add(makeRecord(runPrefix + "S2", Result.Status.SUCCESS, 200, EvaluationRecord.AiRequestStatus.NOT_REQUESTED));
        records.add(makeRecord(runPrefix + "S3", Result.Status.SUCCESS, 300, EvaluationRecord.AiRequestStatus.NOT_REQUESTED));
        records.add(makeRecord(runPrefix + "S4", Result.Status.SUCCESS, 400, EvaluationRecord.AiRequestStatus.NOT_REQUESTED));
        records.add(makeRecord(runPrefix + "C1", Result.Status.COMPILE_ERROR, 50, EvaluationRecord.AiRequestStatus.AVAILABLE));
        records.add(makeRecord(runPrefix + "C2", Result.Status.COMPILE_ERROR, 150, EvaluationRecord.AiRequestStatus.UNAVAILABLE));
        records.add(makeRecord(runPrefix + "R1", Result.Status.RUNTIME_ERROR, 500, EvaluationRecord.AiRequestStatus.AVAILABLE));
        records.add(makeRecord(runPrefix + "R2", Result.Status.RUNTIME_ERROR, 600, EvaluationRecord.AiRequestStatus.AVAILABLE));
        records.add(makeRecord(runPrefix + "T1", Result.Status.TIMEOUT, 3000, EvaluationRecord.AiRequestStatus.UNAVAILABLE));
        records.add(makeRecord(runPrefix + "T2", Result.Status.TIMEOUT, 3000, EvaluationRecord.AiRequestStatus.UNAVAILABLE));

        for (EvaluationRecord r : records) {
            repository.save(r);
        }
        System.out.println("[HistoryServiceTest] Seeded " + records.size() + " statistics-test records with prefix "
                + runPrefix);
        return records;
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
                "StatsTestClass",
                "public class StatsTestClass {}",
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

    private static void testStatisticsFromPersistedRecords(EvaluationHistoryService service,
                                                             List<EvaluationRecord> seededDataset)
            throws EvaluationHistoryException {
        // Request at least as many records as were seeded, plus a margin
        // for the earlier single regression-test record, so the entire
        // seeded dataset is guaranteed to be included.
        EvaluationStatistics stats = service.getStatistics(seededDataset.size() + 5);

        boolean ok = stats.getTotalEvaluations() >= seededDataset.size()
                && stats.getSuccessfulEvaluations() >= 4
                && stats.getCompileErrorCount() >= 2
                && stats.getRuntimeErrorCount() >= 2
                && stats.getTimeoutCount() >= 2
                && stats.getAiAvailableCount() >= 3
                && stats.getAiUnavailableCount() >= 3;

        if (ok) {
            pass("getStatistics() returns statistics reflecting the persisted/retrieved records: " + stats);
        } else {
            fail("getStatistics() did not reflect the expected persisted records: " + stats);
        }
    }

    private static void testStatisticsRespectsLimit(EvaluationHistoryService service,
                                                      EvaluationRepository repository,
                                                      String runPrefix)
            throws SQLException, EvaluationHistoryException {
        // Seed 3 more, deliberately-ordered records with a distinct prefix
        // and request statistics with limit=3 — the newest 3 must be the
        // only ones reflected.
        String limitPrefix = runPrefix + "LIMIT-";
        EvaluationRecord r1 = makeRecord(limitPrefix + "1", Result.Status.SUCCESS, 10, EvaluationRecord.AiRequestStatus.NOT_REQUESTED);
        repository.save(r1);
        sleepBriefly();
        EvaluationRecord r2 = makeRecord(limitPrefix + "2", Result.Status.SUCCESS, 20, EvaluationRecord.AiRequestStatus.NOT_REQUESTED);
        repository.save(r2);
        sleepBriefly();
        EvaluationRecord r3 = makeRecord(limitPrefix + "3", Result.Status.SUCCESS, 30, EvaluationRecord.AiRequestStatus.NOT_REQUESTED);
        repository.save(r3);

        EvaluationStatistics stats = service.getStatistics(3);
        List<EvaluationRecord> matchingRecent = service.getRecentEvaluations(3);

        boolean sizeRespected = stats.getTotalEvaluations() == 3;
        boolean matchesRecentList = matchingRecent.size() == 3
                && matchingRecent.get(0).getEvaluationId().equals(r3.getEvaluationId());

        if (sizeRespected && matchesRecentList) {
            pass("getStatistics(3) respects the limit and reflects exactly the 3 most recent records.");
        } else {
            fail("getStatistics(3) did not correctly respect the limit. stats=" + stats
                    + ", mostRecentIdSeen=" + (matchingRecent.isEmpty() ? "none" : matchingRecent.get(0).getEvaluationId()));
        }
    }

    private static void testStatisticsEmptyHistory() throws EvaluationHistoryException {
        // Uses a repository test double that always returns an empty list,
        // rather than relying on the real (non-empty) production/sandbox
        // database — this avoids any dependency on database state.
        EvaluationHistoryService emptyService = new EvaluationHistoryService(new AlwaysEmptyRepository());
        EvaluationStatistics stats = emptyService.getStatistics(10);

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
            pass("getStatistics() on empty history returns valid all-zero EvaluationStatistics: " + stats);
        } else {
            fail("getStatistics() on empty history did not return valid all-zero statistics: " + stats);
        }
    }

    private static void testStatisticsInvalidLimitRejected(EvaluationHistoryService service) {
        try {
            service.getStatistics(0);
            fail("getStatistics(0) should have thrown IllegalArgumentException.");
        } catch (IllegalArgumentException e) {
            pass("getStatistics(0) correctly rejected with: " + e.getMessage());
        } catch (EvaluationHistoryException e) {
            fail("getStatistics(0) threw EvaluationHistoryException instead of IllegalArgumentException.");
        }

        try {
            service.getStatistics(-5);
            fail("getStatistics(-5) should have thrown IllegalArgumentException.");
        } catch (IllegalArgumentException e) {
            pass("getStatistics(-5) correctly rejected with: " + e.getMessage());
        } catch (EvaluationHistoryException e) {
            fail("getStatistics(-5) threw EvaluationHistoryException instead of IllegalArgumentException.");
        }
    }

    private static void testStatisticsExceptionWrapping() {
        EvaluationHistoryService failingService = new EvaluationHistoryService(new AlwaysFailingRepository());

        try {
            failingService.getStatistics(5);
            fail("getStatistics() on a failing repository should have thrown EvaluationHistoryException.");
        } catch (EvaluationHistoryException e) {
            if (e.getCause() instanceof SQLException) {
                pass("getStatistics() correctly wraps a repository failure as EvaluationHistoryException "
                        + "with the original SQLException preserved as cause.");
            } else {
                fail("getStatistics() wrapped the failure, but the cause was not a SQLException: " + e.getCause());
            }
        }
    }

    private static void testStatisticsDelegationCorrectness(EvaluationHistoryService service,
                                                              List<EvaluationRecord> seededDataset)
            throws EvaluationHistoryException {
        // Request exactly the seeded dataset size via a fresh, isolated
        // in-memory repository double seeded with only these exact
        // records — this isolates the check from any other data already
        // present in the shared sandbox database, giving an exact,
        // hand-verifiable expected result that can only be correct if
        // EvaluationHistoryService truly delegates to EvaluationAnalytics
        // rather than reimplementing (and potentially miscalculating)
        // the aggregation itself.
        EvaluationHistoryService isolatedService =
                new EvaluationHistoryService(new InMemoryRepository(seededDataset));

        EvaluationStatistics stats = isolatedService.getStatistics(seededDataset.size());

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
            pass("getStatistics() on an isolated exact dataset matches EvaluationAnalytics's own hand-verified "
                    + "output exactly, confirming correct delegation (no duplicated/divergent calculation "
                    + "logic in EvaluationHistoryService): " + stats);
        } else {
            fail("getStatistics() on an isolated exact dataset did NOT match the expected hand-calculated "
                    + "values — possible calculation duplication/divergence: " + stats);
        }
    }

    private static void testStatisticsDoesNotMutateInput(EvaluationHistoryService service, int minimumExpectedTotal)
            throws EvaluationHistoryException {
        List<EvaluationRecord> before = service.getRecentEvaluations(minimumExpectedTotal + 5);
        List<EvaluationRecord> beforeSnapshot = new ArrayList<>(before);

        service.getStatistics(minimumExpectedTotal + 5);

        List<EvaluationRecord> after = service.getRecentEvaluations(minimumExpectedTotal + 5);

        boolean unchanged = before.size() == after.size() && beforeSnapshot.equals(after);

        if (unchanged) {
            pass("getStatistics() does not mutate the underlying evaluation history "
                    + "(getRecentEvaluations() before/after calls are identical).");
        } else {
            fail("getStatistics() appears to have mutated the underlying evaluation history.");
        }
    }

    private static void testStatisticsRepeatedCallsDeterministic(EvaluationHistoryService service,
                                                                   int minimumExpectedTotal)
            throws EvaluationHistoryException {
        int limit = minimumExpectedTotal + 5;
        EvaluationStatistics first = service.getStatistics(limit);
        EvaluationStatistics second = service.getStatistics(limit);
        EvaluationStatistics third = service.getStatistics(limit);

        boolean allEqual = first.equals(second) && second.equals(third);

        if (allEqual) {
            pass("repeated getStatistics() calls with the same persisted data produce identical, equal results.");
        } else {
            fail("repeated getStatistics() calls produced different results. first=" + first
                    + ", second=" + second + ", third=" + third);
        }
    }

    // =====================================================================
    // Test-only repository doubles — defined here, not in production code.
    // =====================================================================

    /**
     * Test-only repository double that always throws a simulated
     * SQLException from its read methods, used to verify
     * EvaluationHistoryService's exception-wrapping behavior for both
     * plain history retrieval and statistics retrieval.
     */
    private static final class AlwaysFailingRepository extends EvaluationRepository {
        @Override
        public List<EvaluationRecord> findRecent(int limit) throws SQLException {
            throw new SQLException("Simulated database failure for testing purposes.");
        }

        @Override
        public EvaluationRecord findById(String evaluationId) throws SQLException {
            throw new SQLException("Simulated database failure for testing purposes.");
        }
    }

    /**
     * Test-only repository double that always returns an empty list,
     * used to verify the empty-history statistics path without depending
     * on the real database actually being empty (which it will not be,
     * given prior test runs and real usage).
     */
    private static final class AlwaysEmptyRepository extends EvaluationRepository {
        @Override
        public List<EvaluationRecord> findRecent(int limit) throws SQLException {
            return new ArrayList<>();
        }
    }

    /**
     * Test-only repository double backed entirely by an in-memory list
     * supplied at construction time — never touches SQLite/JDBC at all.
     * Used to verify exact, isolated calculation correctness (delegation
     * to EvaluationAnalytics) without any interference from other data
     * already present in a shared database.
     */
    private static final class InMemoryRepository extends EvaluationRepository {
        private final List<EvaluationRecord> records;

        private InMemoryRepository(List<EvaluationRecord> records) {
            this.records = records;
        }

        @Override
        public List<EvaluationRecord> findRecent(int limit) throws SQLException {
            if (limit <= 0) {
                throw new IllegalArgumentException(
                    "InMemoryRepository.findRecent: limit must be positive (was " + limit + ")."
                );
            }
            int upper = Math.min(limit, records.size());
            return new ArrayList<>(records.subList(0, upper));
        }
    }
}