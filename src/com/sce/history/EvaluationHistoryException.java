package com.sce.history;

/**
 * EvaluationHistoryException
 *
 * Checked exception representing a failure to retrieve evaluation history
 * data. Wraps the underlying java.sql.SQLException thrown by
 * EvaluationRepository, so that callers of EvaluationHistoryService
 * (in particular, a future Swing History UI) never need to import or
 * catch java.sql.SQLException directly — the persistence mechanism
 * remains fully encapsulated behind this application-level type.
 *
 * A missing evaluation ID is NOT represented by this exception — that is
 * a normal, valid outcome (see EvaluationHistoryService.getEvaluationById,
 * which returns null for a missing ID, mirroring EvaluationRepository's
 * own findById() contract exactly). This exception exists solely for
 * genuine persistence-layer failures: a database that cannot be reached,
 * a corrupt row, or any other SQLException the repository surfaces.
 *
 * @author Smart Code Evaluator Team
 */
public class EvaluationHistoryException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Constructs an EvaluationHistoryException wrapping an underlying
     * persistence-layer failure.
     *
     * @param message a human-readable description of what history
     *                 operation failed and why
     * @param cause    the underlying SQLException (or other Throwable)
     *                  that caused this failure; must not be null
     */
    public EvaluationHistoryException(String message, Throwable cause) {
        super(message, cause);
    }
}
