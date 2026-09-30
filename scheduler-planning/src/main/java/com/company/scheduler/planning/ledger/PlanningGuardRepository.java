package com.company.scheduler.planning.ledger;

import com.company.scheduler.shared.error.BusinessException;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Company planning guard: the first lock of every planning write transaction (TRD 9.2).
 * Callers must hold an active transaction; the guard row is created on demand so writes
 * never race an absent row even before A wires company creation.
 */
@Repository
public class PlanningGuardRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public PlanningGuardRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Locks the guard row and returns its revision. Must run before any other row lock of the transaction. */
    public long lockGuard(UUID companyId) {
        requireActiveTransaction();
        jdbc.update("INSERT INTO planning_guard(company_id) VALUES (:companyId) ON CONFLICT DO NOTHING",
                new MapSqlParameterSource("companyId", companyId));
        Long revision = jdbc.query("""
                SELECT revision FROM planning_guard WHERE company_id = :companyId FOR UPDATE
                """, new MapSqlParameterSource("companyId", companyId), (rs, i) -> rs.getLong("revision"))
                .stream().findFirst().orElse(null);
        if (revision == null) {
            throw new IllegalStateException("planning_guard row missing for company " + companyId);
        }
        return revision;
    }

    public void bumpRevision(UUID companyId) {
        jdbc.update("UPDATE planning_guard SET revision = revision + 1 WHERE company_id = :companyId",
                new MapSqlParameterSource("companyId", companyId));
    }

    public long currentRevision(UUID companyId) {
        Long revision = jdbc.query("SELECT revision FROM planning_guard WHERE company_id = :companyId",
                        new MapSqlParameterSource("companyId", companyId), (rs, i) -> rs.getLong("revision"))
                .stream().findFirst().orElse(null);
        if (revision == null) throw BusinessException.notFound();
        return revision;
    }

    private static void requireActiveTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Planning writes require an active transaction");
        }
    }
}
