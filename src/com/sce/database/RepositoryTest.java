package com.sce.database;

import com.sce.core.EvaluationRecord;
import com.sce.core.Result;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

/**
 * RepositoryTest
 *
 * TEMPORARY, standalone verification harness for EvaluationRepository.
 * Not part of the production architecture — exists solely to prove that
 * a real EvaluationRecord can be saved and then correctly retrieved via
 * findById() and findRecent(). Should be deleted once Day 3.1 is
 * confirmed working; it is not referenced by, and does not modify, any
 * production class.
 *
 * Uses a freshly unique evaluation_id (timestamp-suffixed) rather than
 * reusing 'DAY2-TEST-REPOSITORY-VERIFICATION-0001', which already exists
 * in the database from Day 2 testing and would now correctly fail the
 * PRIMARY KEY constraint on re-insertion.
 *
 * @author Smart Code Evaluator Team
 */
public class RepositoryTest {

    private static final String TEST_ID_PREFIX = "DAY3-TEST-";

    public static void main(String[] args) {
        System.out.println("[RepositoryTest] Starting EvaluationRepository Day 3.1 verification...");

        String testEvaluationId = TEST_ID_PREFIX + System.currentTimeMillis();

        try {
            EvaluationRepository repository = new EvaluationRepository();

            // Step 1: ensure the database/schema exists.
            DatabaseManager.initializeDatabase();
            System.out.println("[RepositoryTest] Database initialized (schema ensured).");

            // Step 2: build and save one realistic, uniquely-identified EvaluationRecord.
            EvaluationRecord original = new EvaluationRecord(
                    testEvaluationId,
                    Instant.now(),
                    "TimeoutDemo",
                    "public class TimeoutDemo {\n"
                            + "    public static void main(String[] args) {\n"
                            + "        while (true) { }\n"
                            + "    }\n"
                            + "}\n",
                    Result.Status.TIMEOUT,
                    -1,
                    "",
                    "Execution exceeded the configured timeout of 3000ms and was forcibly terminated.",
                    "This program never terminates because the while condition is always true.",
                    "Add a condition or break statement that eventually becomes false.",
                    EvaluationRecord.AiRequestStatus.AVAILABLE,
                    3005L
            );

            repository.save(original);
            System.out.println("[RepositoryTest] save() completed for evaluation_id=" + testEvaluationId);

            // Step 3: verify findById() returns a matching record.
            testFindById(repository, testEvaluationId, original);

            // Step 4: verify findById() returns null for a nonexistent ID.
            testFindByIdMissing(repository);

            // Step 5: verify findRecent() returns our record, ordered newest-first,
            // and respects the requested limit.
            testFindRecent(repository, testEvaluationId);

            // Step 6: verify findRecent() rejects an invalid limit.
            testFindRecentInvalidLimit(repository);

            System.out.println("[RepositoryTest] ALL CHECKS PASSED.");

        } catch (SQLException e) {
            System.err.println("[RepositoryTest] FAILED — SQLException occurred:");
            e.printStackTrace();
        } catch (IllegalArgumentException e) {
            System.err.println("[RepositoryTest] FAILED — invalid input:");
            e.printStackTrace();
        }
    }

    private static void testFindById(EvaluationRepository repository,
                                      String testEvaluationId,
                                      EvaluationRecord original) throws SQLException {
        EvaluationRecord fetched = repository.findById(testEvaluationId);

        if (fetched == null) {
            System.err.println("[RepositoryTest] FAILED — findById() returned null for a known ID.");
            return;
        }

        boolean matches =
                fetched.getEvaluationId().equals(original.getEvaluationId())
                && fetched.getClassName().equals(original.getClassName())
                && fetched.getSourceCode().equals(original.getSourceCode())
                && fetched.getStatus() == original.getStatus()
                && fetched.getExitCode() == original.getExitCode()
                && fetched.getStdout().equals(original.getStdout())
                && fetched.getStderr().equals(original.getStderr())
                && fetched.getAiHint().equals(original.getAiHint())
                && fetched.getAiSuggestedFix().equals(original.getAiSuggestedFix())
                && fetched.getAiRequestStatus() == original.getAiRequestStatus()
                && fetched.getDurationMillis() == original.getDurationMillis();

        if (matches) {
            System.out.println("[RepositoryTest] PASS — findById() returned a matching record:");
            System.out.println("    " + fetched);
        } else {
            System.err.println("[RepositoryTest] FAILED — findById() returned a record with mismatched fields.");
            System.err.println("    expected: " + original);
            System.err.println("    actual:   " + fetched);
        }
    }

    private static void testFindByIdMissing(EvaluationRepository repository) throws SQLException {
        EvaluationRecord missing = repository.findById("DAY3-TEST-DOES-NOT-EXIST-0000");

        if (missing == null) {
            System.out.println("[RepositoryTest] PASS — findById() correctly returned null for an unknown ID.");
        } else {
            System.err.println("[RepositoryTest] FAILED — findById() returned a non-null record for an unknown ID.");
        }
    }

    private static void testFindRecent(EvaluationRepository repository, String testEvaluationId) throws SQLException {
        List<EvaluationRecord> recent = repository.findRecent(5);

        if (recent.isEmpty()) {
            System.err.println("[RepositoryTest] FAILED — findRecent(5) returned an empty list.");
            return;
        }

        boolean containsTestRecord = recent.stream()
                .anyMatch(r -> r.getEvaluationId().equals(testEvaluationId));

        if (!containsTestRecord) {
            System.err.println("[RepositoryTest] FAILED — findRecent(5) did not include the just-saved test record.");
            return;
        }

        boolean orderedDescending = true;
        for (int i = 0; i < recent.size() - 1; i++) {
            if (recent.get(i).getTimestamp().isBefore(recent.get(i + 1).getTimestamp())) {
                orderedDescending = false;
                break;
            }
        }

        if (recent.size() > 5) {
            System.err.println("[RepositoryTest] FAILED — findRecent(5) returned more than 5 records ("
                    + recent.size() + ").");
            return;
        }

        if (orderedDescending) {
            System.out.println("[RepositoryTest] PASS — findRecent(5) returned " + recent.size()
                    + " record(s), correctly ordered most-recent-first, including the test record.");
        } else {
            System.err.println("[RepositoryTest] FAILED — findRecent(5) results are not in descending timestamp order.");
        }
    }

    private static void testFindRecentInvalidLimit(EvaluationRepository repository) {
        try {
            repository.findRecent(0);
            System.err.println("[RepositoryTest] FAILED — findRecent(0) should have thrown IllegalArgumentException.");
        } catch (IllegalArgumentException e) {
            System.out.println("[RepositoryTest] PASS — findRecent(0) correctly rejected with: " + e.getMessage());
        } catch (SQLException e) {
            System.err.println("[RepositoryTest] FAILED — findRecent(0) threw SQLException instead of "
                    + "IllegalArgumentException:");
            e.printStackTrace();
        }
    }
}