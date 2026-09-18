package com.sce.history;

import com.sce.analytics.EvaluationAnalytics;
import com.sce.analytics.EvaluationStatistics;
import com.sce.core.EvaluationRecord;
import com.sce.database.EvaluationRepository;

import java.sql.SQLException;
import java.util.Collections;
import java.util.List;

/**
 * EvaluationHistoryService
 *
 * Application-level History layer, sitting between a future History UI
 * (or Analytics / REST API layer) and EvaluationRepository. Its single
 * responsibility is to expose history retrieval operations using
 * application-level parameters and return types, while fully
 * encapsulating the fact that persistence is implemented via JDBC/SQLite.
 *
 * DEPENDENCY DIRECTION:
 * EvaluationHistoryService
 *        ↓
 * EvaluationRepository
 *
 * For analytics:
 * EvaluationHistoryService
 *        ↓
 * EvaluationAnalytics
 *        ↓
 * EvaluationStatistics
 *
 * This class has no dependency on Swing, SystemController, or any UI class.
 *
 * THIS IS NOT A SECOND REPOSITORY: it contains no SQL, no JDBC types
 * in its public API, and no query logic of its own. All persistence
 * operations are delegated to EvaluationRepository.
 *
 * STATELESS / THREAD-SAFE:
 * this class holds only references to its repository and stateless
 * analytics calculator. No caching, shared mutable state, locking,
 * or background threads are introduced here.
 *
 * @author Smart Code Evaluator Team
 */
public class EvaluationHistoryService {

    private final EvaluationRepository evaluationRepository;
    private final EvaluationAnalytics evaluationAnalytics;

    /**
     * Constructs an EvaluationHistoryService backed by a new
     * EvaluationRepository instance.
     */
    public EvaluationHistoryService() {
        this.evaluationRepository = new EvaluationRepository();
        this.evaluationAnalytics = new EvaluationAnalytics();
    }

    /**
     * Constructs an EvaluationHistoryService backed by the given
     * EvaluationRepository.
     *
     * Provided primarily for testability so tests can supply a
     * repository or test double without this service knowing how
     * persistence is implemented.
     *
     * @param evaluationRepository the repository to delegate persistence
     *                             operations to; must not be null
     * @throws IllegalArgumentException if evaluationRepository is null
     */
    public EvaluationHistoryService(EvaluationRepository evaluationRepository) {
        if (evaluationRepository == null) {
            throw new IllegalArgumentException(
                "EvaluationHistoryService: evaluationRepository must not be null."
            );
        }

        this.evaluationRepository = evaluationRepository;
        this.evaluationAnalytics = new EvaluationAnalytics();
    }

    /**
     * Retrieves the most recent evaluations, most-recent-first,
     * bounded to at most 'limit' records.
     *
     * Delegates directly to EvaluationRepository.findRecent(limit).
     *
     * The returned List is unmodifiable.
     *
     * @param limit the maximum number of records to return; must be
     *              a positive integer
     *
     * @return an unmodifiable List of EvaluationRecord, most recent
     *         first; never null, may be empty
     *
     * @throws IllegalArgumentException if limit is zero or negative
     * @throws EvaluationHistoryException if the repository operation
     *                                    fails due to a persistence error
     */
    public List<EvaluationRecord> getRecentEvaluations(int limit)
            throws EvaluationHistoryException {

        try {
            List<EvaluationRecord> recent =
                    evaluationRepository.findRecent(limit);

            return Collections.unmodifiableList(recent);

        } catch (SQLException e) {
            throw new EvaluationHistoryException(
                "Failed to retrieve the " + limit
                        + " most recent evaluations.",
                e
            );
        }
    }

    /**
     * Retrieves a single evaluation by its unique ID.
     *
     * @param evaluationId the evaluation ID to look up; must not be null
     *
     * @return the matching EvaluationRecord, or null if no evaluation
     *         exists with the specified ID
     *
     * @throws IllegalArgumentException if evaluationId is null
     * @throws EvaluationHistoryException if the repository operation
     *                                    fails due to a persistence error
     */
    public EvaluationRecord getEvaluationById(String evaluationId)
            throws EvaluationHistoryException {

        try {
            return evaluationRepository.findById(evaluationId);

        } catch (SQLException e) {
            throw new EvaluationHistoryException(
                "Failed to retrieve evaluation with id='"
                        + evaluationId + "'.",
                e
            );
        }
    }

    /**
     * Calculates aggregated statistics for the most recent evaluations.
     *
     * The history records are retrieved through the existing
     * getRecentEvaluations(limit) method and then delegated directly
     * to the stateless EvaluationAnalytics calculation layer.
     *
     * No statistics calculation is performed inside this service.
     * This keeps the dependency direction clean:
     *
     * EvaluationHistoryService
     *        ↓
     * EvaluationAnalytics
     *        ↓
     * EvaluationStatistics
     *
     * @param limit maximum number of recent evaluations to include;
     *              must be positive
     *
     * @return immutable EvaluationStatistics snapshot; never null
     *
     * @throws IllegalArgumentException if limit is zero or negative
     * @throws EvaluationHistoryException if history retrieval fails
     */
    public EvaluationStatistics getStatistics(int limit)
            throws EvaluationHistoryException {

        List<EvaluationRecord> records = getRecentEvaluations(limit);

        return evaluationAnalytics.calculateStatistics(records);
    }
}
