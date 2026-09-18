package com.sce.database;

import com.sce.core.EvaluationRecord;
import com.sce.core.EvaluationRecord.AiRequestStatus;
import com.sce.core.Result;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * EvaluationRepository
 *
 * Persists completed EvaluationRecord instances into, and retrieves them
 * from, the SQLite 'evaluations' table via JDBC. This class is a pure
 * persistence component: it depends only on com.sce.core.EvaluationRecord
 * (and its nested Result.Status / AiRequestStatus enums) and
 * com.sce.database.DatabaseManager. It has no dependency on Swing,
 * SystemController, ExecutionEngine, or AIConnector.
 *
 * RESOURCE LIFECYCLE: every method obtains a short-lived Connection (and,
 * where applicable, PreparedStatement and ResultSet) via try-with-resources
 * and closes them before returning, win or lose. No JDBC resource is ever
 * held as instance state, which is what makes this class safe to call
 * concurrently from multiple threads (e.g. a future History UI's
 * background thread reading while the evaluation worker thread writes)
 * without any shared-connection or locking concerns.
 *
 * READ METHODS (Day 3.1): findById() and findRecent() both return plain
 * EvaluationRecord objects (or a List of them) — no ResultSet, Connection,
 * or other JDBC-specific type is ever exposed to a caller. Both share a
 * single row-mapping implementation, mapRow(), so the SQLite-column-to-
 * domain-field conversion exists in exactly one place.
 *
 * findAll() is deliberately not provided: it would have no bound on
 * result size or memory usage as the table grows over the life of the
 * project, and nothing in the current architecture requires an unbounded
 * listing. findRecent(limit) covers the actual "Evaluation History" use
 * case with an explicit, caller-controlled bound.
 *
 * @author Smart Code Evaluator Team
 */
public class EvaluationRepository {

    private static final String INSERT_SQL =
            "INSERT INTO evaluations (" +
            "evaluation_id, timestamp, class_name, source_code, status, " +
            "exit_code, stdout, stderr, ai_hint, ai_suggested_fix, " +
            "ai_request_status, duration_millis" +
            ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String SELECT_BY_ID_SQL =
            "SELECT evaluation_id, timestamp, class_name, source_code, status, " +
            "exit_code, stdout, stderr, ai_hint, ai_suggested_fix, " +
            "ai_request_status, duration_millis " +
            "FROM evaluations WHERE evaluation_id = ?";

    /**
     * ORDER BY timestamp DESC relies on 'timestamp' being stored as the
     * ISO-8601 string produced by Instant.toString() in save() below —
     * ISO-8601 extended format is lexicographically sortable in the same
     * order as chronological order, so a plain string ORDER BY produces
     * correct most-recent-first ordering without a separate numeric
     * column or application-side sort.
     */
    private static final String SELECT_RECENT_SQL =
            "SELECT evaluation_id, timestamp, class_name, source_code, status, " +
            "exit_code, stdout, stderr, ai_hint, ai_suggested_fix, " +
            "ai_request_status, duration_millis " +
            "FROM evaluations ORDER BY timestamp DESC LIMIT ?";

    /**
     * Guards one-time schema initialization. Not JDBC state — a single
     * boolean flag — so it introduces no connection-sharing risk and
     * requires no synchronized block; compareAndSet() is sufficient.
     */
    private final AtomicBoolean schemaInitialized = new AtomicBoolean(false);

    /**
     * Persists the given EvaluationRecord as a new row in the
     * 'evaluations' table. Every field is mapped to its corresponding
     * column via a parameterized PreparedStatement — no string
     * concatenation is used anywhere in the SQL, eliminating SQL
     * injection risk even though source code and output text are
     * user-influenced content.
     *
     * UNCHANGED from Day 2 — behavior, signature, and SQL are identical.
     *
     * @param record the completed evaluation to persist; must not be null
     * @throws IllegalArgumentException if record is null
     * @throws SQLException              if the schema cannot be
     *                                     initialized, the connection
     *                                     cannot be obtained, or the
     *                                     insert fails for any reason —
     *                                     including a duplicate
     *                                     evaluation_id, which SQLite
     *                                     reports as a constraint-
     *                                     violation SQLException.
     */
    public void save(EvaluationRecord record) throws SQLException {
        if (record == null) {
            throw new IllegalArgumentException("EvaluationRepository.save: record must not be null.");
        }

        ensureSchemaInitialized();

        try (Connection connection = DatabaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {

            statement.setString(1, record.getEvaluationId());
            statement.setString(2, record.getTimestamp().toString());
            statement.setString(3, record.getClassName());
            statement.setString(4, record.getSourceCode());
            statement.setString(5, record.getStatus().name());
            statement.setInt(6, record.getExitCode());
            statement.setString(7, record.getStdout());
            statement.setString(8, record.getStderr());
            statement.setString(9, record.getAiHint());
            statement.setString(10, record.getAiSuggestedFix());
            statement.setString(11, record.getAiRequestStatus().name());
            statement.setLong(12, record.getDurationMillis());

            statement.executeUpdate();
        }
    }

    /**
     * Retrieves the EvaluationRecord with the given evaluationId, if one
     * exists.
     *
     * @param evaluationId the evaluation_id to look up; must not be null
     * @return the matching EvaluationRecord, or null if no row exists
     *          with this ID
     * @throws IllegalArgumentException if evaluationId is null
     * @throws SQLException              if the schema cannot be
     *                                     initialized, the connection
     *                                     cannot be obtained, the query
     *                                     fails, or a stored status/
     *                                     ai_request_status value cannot
     *                                     be mapped back to a known enum
     *                                     constant (see mapRow())
     */
    public EvaluationRecord findById(String evaluationId) throws SQLException {
        if (evaluationId == null) {
            throw new IllegalArgumentException("EvaluationRepository.findById: evaluationId must not be null.");
        }

        ensureSchemaInitialized();

        try (Connection connection = DatabaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_BY_ID_SQL)) {

            statement.setString(1, evaluationId);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return mapRow(resultSet);
            }
        }
    }

    /**
     * Retrieves the most recent evaluations, ordered newest first, bounded
     * to at most 'limit' rows.
     *
     * @param limit the maximum number of records to return; must be a
     *               positive integer (1 or greater)
     * @return a List of EvaluationRecord, most recent first, containing
     *          between 0 and 'limit' elements; never null
     * @throws IllegalArgumentException if limit is zero or negative
     * @throws SQLException              if the schema cannot be
     *                                     initialized, the connection
     *                                     cannot be obtained, the query
     *                                     fails, or a stored enum value
     *                                     cannot be mapped (see mapRow())
     */
    public List<EvaluationRecord> findRecent(int limit) throws SQLException {
        if (limit <= 0) {
            throw new IllegalArgumentException(
                "EvaluationRepository.findRecent: limit must be positive (was " + limit + ")."
            );
        }

        ensureSchemaInitialized();

        List<EvaluationRecord> records = new ArrayList<>();

        try (Connection connection = DatabaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_RECENT_SQL)) {

            statement.setInt(1, limit);

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    records.add(mapRow(resultSet));
                }
            }
        }

        return records;
    }

    /**
     * Maps the current row of the given ResultSet into a fully populated,
     * immutable EvaluationRecord. Fully consumes the current row's column
     * values before returning — the caller's try-with-resources block
     * remains responsible for advancing/closing the ResultSet itself;
     * this method never retains a reference to it.
     *
     * Enum columns (status, ai_request_status) are converted via
     * Enum.valueOf(...). If a stored value does not match any known
     * constant — which would indicate either manual database tampering
     * or a future schema/enum drift — this is treated as a genuine data
     * integrity error: it is NOT silently mapped to a default/fallback
     * status, since doing so could misrepresent, e.g., a RUNTIME_ERROR
     * row as SUCCESS. Instead, the IllegalArgumentException thrown by
     * valueOf() is caught here and rethrown as a SQLException carrying
     * the offending evaluation_id, column, and raw value — consistent
     * with this repository's existing convention (matching how
     * DatabaseManager already wraps ClassNotFoundException as
     * SQLException) of surfacing all persistence-layer failures through
     * a single declared exception type.
     *
     * @param resultSet a ResultSet currently positioned on a valid row
     * @return the mapped EvaluationRecord
     * @throws SQLException if a column cannot be read, or if a stored
     *                        enum value does not match any known constant
     */
    private EvaluationRecord mapRow(ResultSet resultSet) throws SQLException {
        String evaluationId = resultSet.getString("evaluation_id");

        Instant timestamp = Instant.parse(resultSet.getString("timestamp"));
        String className = resultSet.getString("class_name");
        String sourceCode = resultSet.getString("source_code");

        String rawStatus = resultSet.getString("status");
        Result.Status status;
        try {
            status = Result.Status.valueOf(rawStatus);
        } catch (IllegalArgumentException e) {
            throw new SQLException(
                "EvaluationRepository: corrupt data — evaluation_id='" + evaluationId +
                "' has an unrecognized 'status' value '" + rawStatus + "' that does not match " +
                "any known Result.Status constant.", e
            );
        }

        int exitCode = resultSet.getInt("exit_code");
        String stdout = resultSet.getString("stdout");
        String stderr = resultSet.getString("stderr");
        String aiHint = resultSet.getString("ai_hint");
        String aiSuggestedFix = resultSet.getString("ai_suggested_fix");

        String rawAiRequestStatus = resultSet.getString("ai_request_status");
        AiRequestStatus aiRequestStatus;
        try {
            aiRequestStatus = AiRequestStatus.valueOf(rawAiRequestStatus);
        } catch (IllegalArgumentException e) {
            throw new SQLException(
                "EvaluationRepository: corrupt data — evaluation_id='" + evaluationId +
                "' has an unrecognized 'ai_request_status' value '" + rawAiRequestStatus +
                "' that does not match any known AiRequestStatus constant.", e
            );
        }

        long durationMillis = resultSet.getLong("duration_millis");

        return new EvaluationRecord(
                evaluationId,
                timestamp,
                className,
                sourceCode,
                status,
                exitCode,
                stdout,
                stderr,
                aiHint,
                aiSuggestedFix,
                aiRequestStatus,
                durationMillis
        );
    }

    /**
     * Ensures the 'evaluations' table exists, running
     * DatabaseManager.initializeDatabase() at most once per
     * EvaluationRepository instance.
     *
     * UNCHANGED from Day 2.
     *
     * @throws SQLException if schema initialization fails
     */
    private void ensureSchemaInitialized() throws SQLException {
        if (schemaInitialized.compareAndSet(false, true)) {
            DatabaseManager.initializeDatabase();
        }
    }
}