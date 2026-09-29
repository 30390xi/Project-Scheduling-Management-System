package com.company.scheduler.shared.contract;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Read port for planning. Actor authorization must be checked before returning project data. */
public interface TaskCatalog {
    List<TaskSnapshot> loadSchedulable(UUID actorId, UUID companyId, List<UUID> taskIds);

    record TaskSnapshot(UUID taskId, UUID projectId, long version, int estimatedMinutes,
                        LocalDate earliestStart, LocalDate hardDeadline, List<SkillRequirement> skills,
                        List<UUID> predecessors, UUID fixedAssigneeId) {
        public TaskSnapshot {
            skills = List.copyOf(skills);
            predecessors = List.copyOf(predecessors);
        }
    }

    record SkillRequirement(String code, int minimumLevel) {}
}
