package com.company.scheduler.shared.contract;
import java.util.UUID;
public interface PlanningWork {
    void generate(UUID companyId, UUID planId);
}
