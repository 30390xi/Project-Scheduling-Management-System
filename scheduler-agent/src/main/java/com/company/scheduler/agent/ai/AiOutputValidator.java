package com.company.scheduler.agent.ai;

import com.company.scheduler.agent.AgentJson;
import com.company.scheduler.shared.error.BusinessException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 结构化输出校验：JSON 严格解析 → Schema 形状 → 领域规则（TRD 7.4）。
 * 校验是纯确定性代码，不信任模型自报内容；模型不能确认假设、不能引用目录外技能、
 * 拆解必须无环且数量受限。
 */
@Component
public class AiOutputValidator {

    private static final Pattern TASK_TYPE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");
    private static final Pattern CLIENT_KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{0,31}$");

    private final AiGatewayProperties properties;

    public AiOutputValidator(AiGatewayProperties properties) {
        this.properties = properties;
    }

    public AiValidationResult validate(String structuredJson, List<String> allowedSkillCodes) {
        AiAnalysisOutput output;
        try {
            output = AgentJson.fromJson(structuredJson, AiAnalysisOutput.class);
        } catch (BusinessException jsonError) {
            // Jackson 把未知字段也报为解析失败；按消息区分解析错误与结构违规
            boolean unknownField = jsonError.getMessage() != null
                    && jsonError.getMessage().contains("Unrecognized field");
            return AiValidationResult.invalid(List.of(new AiValidationResult.Violation(
                    unknownField ? AiValidationResult.Codes.SCHEMA_INVALID
                            : AiValidationResult.Codes.JSON_INVALID,
                    jsonError.getMessage())));
        }
        List<AiValidationResult.Violation> violations = new ArrayList<>();
        validateHeader(output, violations);
        // 根估算只有在“未拆解”时才是叶子级估算；拆解树的父级是汇总，不受单叶子上限约束
        validateEstimate(output.estimate(), output.subtasks().isEmpty(), violations);
        validateSkills(output.requiredSkills(), allowedSkillCodes, violations);
        validateAssumptions(output.assumptions(), violations);
        validateSubtasks(output, allowedSkillCodes, violations);
        validateDependencies(output, violations);
        if (violations.isEmpty()) {
            return new AiValidationResult(true, List.of(), output);
        }
        return AiValidationResult.invalid(violations);
    }

    private void validateHeader(AiAnalysisOutput output, List<AiValidationResult.Violation> violations) {
        if (!AiPrompts.SCHEMA_VERSION.equals(output.schemaVersion())) {
            violations.add(new AiValidationResult.Violation(
                    AiValidationResult.Codes.SCHEMA_VERSION_UNSUPPORTED,
                    "schemaVersion 必须是 " + AiPrompts.SCHEMA_VERSION));
        }
        if (output.taskType() == null || !TASK_TYPE.matcher(output.taskType()).matches()) {
            violations.add(new AiValidationResult.Violation(AiValidationResult.Codes.TASK_TYPE_INVALID,
                    "taskType 必须是大写下划线标识"));
        }
        if (output.summary() == null || output.summary().isBlank()) {
            violations.add(new AiValidationResult.Violation(AiValidationResult.Codes.SUMMARY_MISSING,
                    "summary 不能为空"));
        }
        if (output.requiresDecomposition() == null) {
            violations.add(new AiValidationResult.Violation(AiValidationResult.Codes.SCHEMA_INVALID,
                    "requiresDecomposition 缺失"));
        }
        if (output.confidence() != null && (output.confidence() < 0 || output.confidence() > 1)) {
            violations.add(new AiValidationResult.Violation(
                    AiValidationResult.Codes.CONFIDENCE_OUT_OF_RANGE, "confidence 必须在 [0,1]"));
        }
        if (output.missingInformation().size() > 5) {
            violations.add(new AiValidationResult.Violation(
                    AiValidationResult.Codes.MISSING_INFO_LIMIT_EXCEEDED, "missingInformation 最多 5 条"));
        }
        if (Boolean.TRUE.equals(output.requiresDecomposition()) && output.subtasks().isEmpty()) {
            violations.add(new AiValidationResult.Violation(
                    AiValidationResult.Codes.DECOMPOSITION_FLAG_CONFLICT,
                    "requiresDecomposition=true 但没有 subtasks"));
        }
        if (Boolean.FALSE.equals(output.requiresDecomposition()) && !output.subtasks().isEmpty()) {
            violations.add(new AiValidationResult.Violation(
                    AiValidationResult.Codes.DECOMPOSITION_FLAG_CONFLICT,
                    "requiresDecomposition=false 但给出了 subtasks"));
        }
    }

    private void validateEstimate(AiAnalysisOutput.Estimate estimate, boolean leafLevel,
            List<AiValidationResult.Violation> violations) {
        if (estimate == null) {
            violations.add(new AiValidationResult.Violation(AiValidationResult.Codes.ESTIMATE_MISSING,
                    "缺少 estimate"));
            return;
        }
        Integer optimistic = estimate.optimisticMinutes();
        Integer likely = estimate.likelyMinutes();
        Integer pessimistic = estimate.pessimisticMinutes();
        if (optimistic == null || likely == null || pessimistic == null
                || optimistic <= 0 || likely <= 0 || pessimistic <= 0) {
            violations.add(new AiValidationResult.Violation(
                    AiValidationResult.Codes.ESTIMATE_NOT_POSITIVE, "估算分钟数必须为正整数"));
            return;
        }
        if (!(optimistic <= likely && likely <= pessimistic)) {
            violations.add(new AiValidationResult.Violation(
                    AiValidationResult.Codes.ESTIMATE_ORDER_INVALID,
                    "必须满足 optimistic <= likely <= pessimistic"));
        }
        if (leafLevel && pessimistic > properties.maxLeafTaskMinutes()) {
            violations.add(new AiValidationResult.Violation(
                    AiValidationResult.Codes.ESTIMATE_OVERSIZE,
                    "单叶子估算 " + pessimistic + " 分钟超过上限 " + properties.maxLeafTaskMinutes()));
        }
    }

    private void validateSkills(List<AiAnalysisOutput.SkillRequirement> skills,
            List<String> allowedSkillCodes, List<AiValidationResult.Violation> violations) {
        Set<String> allowed = new HashSet<>(allowedSkillCodes);
        for (AiAnalysisOutput.SkillRequirement skill : skills) {
            if (skill.code() == null || !allowed.contains(skill.code())) {
                violations.add(new AiValidationResult.Violation(AiValidationResult.Codes.SKILL_UNKNOWN,
                        "技能 " + skill.code() + " 不在公司目录；新技能只能提议，不能直接引用"));
            }
            if (skill.minLevel() == null || skill.minLevel() < 1 || skill.minLevel() > 5) {
                violations.add(new AiValidationResult.Violation(
                        AiValidationResult.Codes.SKILL_LEVEL_INVALID,
                        "技能 " + skill.code() + " 的 minLevel 必须在 1..5"));
            }
        }
    }

    private void validateAssumptions(List<AiAnalysisOutput.Assumption> assumptions,
            List<AiValidationResult.Violation> violations) {
        for (AiAnalysisOutput.Assumption assumption : assumptions) {
            if (Boolean.TRUE.equals(assumption.confirmed())) {
                violations.add(new AiValidationResult.Violation(
                        AiValidationResult.Codes.FORGED_CONFIRMATION,
                        "模型不能确认假设：" + assumption.text()));
            }
        }
    }

    private void validateSubtasks(AiAnalysisOutput output, List<String> allowedSkillCodes,
            List<AiValidationResult.Violation> violations) {
        List<AiAnalysisOutput.Subtask> subtasks = output.subtasks();
        if (subtasks.size() > properties.maxSubtasks()) {
            violations.add(new AiValidationResult.Violation(
                    AiValidationResult.Codes.SUBTASK_LIMIT_EXCEEDED,
                    "子任务数量 " + subtasks.size() + " 超过上限 " + properties.maxSubtasks()));
        }
        Set<String> keys = new HashSet<>();
        for (AiAnalysisOutput.Subtask subtask : subtasks) {
            if (subtask.clientKey() == null || !CLIENT_KEY.matcher(subtask.clientKey()).matches()) {
                violations.add(new AiValidationResult.Violation(AiValidationResult.Codes.SUBTASK_KEY_INVALID,
                        "clientKey 非法: " + subtask.clientKey()));
            } else if (!keys.add(subtask.clientKey())) {
                violations.add(new AiValidationResult.Violation(AiValidationResult.Codes.SUBTASK_KEY_INVALID,
                        "clientKey 重复: " + subtask.clientKey()));
            }
            if (subtask.title() == null || subtask.title().isBlank()
                    || subtask.deliverables().isEmpty() || subtask.acceptanceCriteria().isEmpty()) {
                violations.add(new AiValidationResult.Violation(
                        AiValidationResult.Codes.SUBTASK_FIELD_MISSING,
                        "子任务 " + subtask.clientKey() + " 缺少标题/交付物/验收标准"));
            }
            validateEstimate(subtask.estimate(), false, violations);
            validateSkills(subtask.requiredSkills(), allowedSkillCodes, violations);
        }
    }

    private void validateDependencies(AiAnalysisOutput output,
            List<AiValidationResult.Violation> violations) {
        Set<String> nodes = new HashSet<>();
        output.subtasks().forEach(subtask -> nodes.add(subtask.clientKey()));
        Map<String, List<String>> adjacency = new HashMap<>();
        for (AiAnalysisOutput.DependencyEdge edge : output.dependencies()) {
            if (edge.from() == null || edge.to() == null || !"FS".equals(edge.type())) {
                violations.add(new AiValidationResult.Violation(AiValidationResult.Codes.SCHEMA_INVALID,
                        "依赖边必须形如 {from,to,type:\"FS\"}"));
                continue;
            }
            if (edge.from().equals(edge.to())) {
                violations.add(new AiValidationResult.Violation(AiValidationResult.Codes.DEPENDENCY_SELF,
                        "自依赖: " + edge.from()));
                continue;
            }
            if (!nodes.contains(edge.from()) || !nodes.contains(edge.to())) {
                violations.add(new AiValidationResult.Violation(
                        AiValidationResult.Codes.DEPENDENCY_UNKNOWN_NODE,
                        "依赖引用不存在的 clientKey: " + edge.from() + " -> " + edge.to()));
                continue;
            }
            adjacency.computeIfAbsent(edge.from(), key -> new ArrayList<>()).add(edge.to());
        }
        if (!adjacency.isEmpty() && containsCycle(nodes, adjacency)) {
            violations.add(new AiValidationResult.Violation(AiValidationResult.Codes.DEPENDENCY_CYCLE,
                    "拆解依赖存在环路"));
        }
    }

    /** Kahn 拓扑排序环检测。 */
    private boolean containsCycle(Set<String> nodes, Map<String, List<String>> adjacency) {
        Map<String, Integer> inDegree = new HashMap<>();
        nodes.forEach(node -> inDegree.put(node, 0));
        adjacency.values().forEach(targets -> targets.forEach(
                target -> inDegree.merge(target, 1, Integer::sum)));
        java.util.ArrayDeque<String> ready = new java.util.ArrayDeque<>(
                nodes.stream().filter(node -> inDegree.get(node) == 0).toList());
        int processed = 0;
        while (!ready.isEmpty()) {
            String current = ready.poll();
            processed++;
            for (String next : adjacency.getOrDefault(current, List.of())) {
                int remaining = inDegree.merge(next, -1, Integer::sum);
                if (remaining == 0) {
                    ready.add(next);
                }
            }
        }
        return processed != nodes.size();
    }
}
