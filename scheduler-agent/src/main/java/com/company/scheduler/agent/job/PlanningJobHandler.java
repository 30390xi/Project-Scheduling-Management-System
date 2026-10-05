package com.company.scheduler.agent.job;
import com.company.scheduler.shared.contract.PlanningWork;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
@Component
public class PlanningJobHandler implements JobHandler {
    private final ObjectProvider<PlanningWork> planning;
    private final AsyncJobRepository jobs;
    private final AgentJobProperties properties;
    private final TransactionTemplate tx;
    public PlanningJobHandler(ObjectProvider<PlanningWork> planning, AsyncJobRepository jobs,
                              AgentJobProperties properties, TransactionTemplate tx) {
        this.planning = planning; this.jobs = jobs; this.properties = properties; this.tx = tx;
    }
    public String jobType() { return "SCHEDULE_SIMULATION"; }
    public void handle(ClaimedJob job) {
        var worker = planning.getIfAvailable();
        if (worker == null) throw new PermanentJobFailure("PLANNING_UNAVAILABLE", "排期引擎不可用");
        tx.executeWithoutResult(status -> {
            if (!jobs.renewLease(job.id(), job.leaseOwner(), job.leaseGeneration(), Instant.now().plus(properties.leaseDuration())))
                throw new PermanentJobFailure("JOB_LEASE_LOST", "排期作业租约已失效");
            worker.generate(job.companyId(), job.aggregateId());
        });
    }
}
