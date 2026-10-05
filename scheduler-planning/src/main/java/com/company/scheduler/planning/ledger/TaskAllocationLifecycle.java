package com.company.scheduler.planning.ledger;
import com.company.scheduler.shared.contract.AllocationLifecycle;
import com.company.scheduler.shared.error.BusinessException;
import com.company.scheduler.planning.support.CompanyRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class TaskAllocationLifecycle implements AllocationLifecycle {
    private final JdbcTemplate jdbc;
    private final CompanyRepository companies;
    private final CapacityLedgerService ledger;
    public TaskAllocationLifecycle(JdbcTemplate jdbc, CompanyRepository companies, CapacityLedgerService ledger) {
        this.jdbc = jdbc; this.companies = companies; this.ledger = ledger;
    }
    public void start(UUID companyId, UUID taskId, UUID memberId) {
        int changed = jdbc.update("""
            UPDATE task_allocation SET execution_started = true
            WHERE company_id = ? AND task_id = ? AND member_id = ? AND work_date = ?
              AND occupancy_state = 'RESERVED'
            """, companyId, taskId, memberId, companies.today(companyId));
        if (changed != 1) throw new BusinessException(HttpStatus.CONFLICT,
                "NO_ALLOCATION_TODAY", "当前负责人今天没有该任务的有效排期，不能开工");
    }
    public void close(UUID companyId, UUID taskId) {
        var today = companies.today(companyId);
        jdbc.update("""
            UPDATE task_allocation SET occupancy_state = 'RETAINED'
            WHERE company_id = ? AND task_id = ? AND occupancy_state = 'RESERVED'
              AND (work_date < ? OR (work_date = ? AND execution_started))
            """, companyId, taskId, today, today);
        var releasable = jdbc.queryForList("""
            SELECT id FROM task_allocation WHERE company_id = ? AND task_id = ?
              AND occupancy_state = 'RESERVED' AND NOT execution_started AND work_date >= ? ORDER BY id
            """, UUID.class, companyId, taskId, today);
        if (!releasable.isEmpty()) ledger.release(companyId, releasable);
    }
}
