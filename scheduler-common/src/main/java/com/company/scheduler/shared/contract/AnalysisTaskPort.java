package com.company.scheduler.shared.contract;
import java.util.List;
import java.util.UUID;
public interface AnalysisTaskPort {
    AnalysisRequests.Request request(UUID actor, UUID company, UUID taskId);
    void authorize(UUID actor, UUID company, UUID taskId);
    List<UUID> confirm(UUID actor, UUID company, UUID taskId, long taskVersion,
                       List<Leaf> leaves, List<Edge> edges);
    record Leaf(String key, String title, String scope, int minutes,
                List<TaskCatalog.SkillRequirement> skills, List<String> acceptanceCriteria) {}
    record Edge(String from, String to) {}
}
