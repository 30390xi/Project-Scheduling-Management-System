package com.company.scheduler.shared.contract;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
public interface TaskPlanningPort {
    List<Task> load(UUID actorId, UUID companyId, List<UUID> rootTaskIds);
    List<UUID> eligibleProjectMembers(UUID actorId, UUID companyId, UUID projectId);
    void requireManager(UUID actorId, UUID companyId, UUID taskId);
    void assign(UUID actorId, UUID companyId, UUID taskId, long version, UUID memberId);
    record Task(UUID id, UUID projectId, long version, String title, String status, String scheduleStatus,
                int minutes, LocalDate earliestStart, LocalDate hardDeadline, LocalDate completedDate,
                UUID fixedAssignee, UUID currentAssignee, UUID preferredMember,
                List<UUID> predecessors, List<TaskCatalog.SkillRequirement> skills, String priority, boolean movable) {}
}
