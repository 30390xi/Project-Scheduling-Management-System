package com.company.scheduler.agent.ai;

import java.util.List;

/** 领域校验结果。violation 为机器码，供修复提示、审计与测试断言使用。 */
public record AiValidationResult(boolean valid, List<Violation> violations, AiAnalysisOutput output) {

    public record Violation(String code, String detail) {}

    public static AiValidationResult invalid(List<Violation> violations) {
        return new AiValidationResult(false, List.copyOf(violations), null);
    }

    public boolean has(String code) {
        return violations.stream().anyMatch(v -> v.code().equals(code));
    }

    public String describe() {
        return violations.stream().map(v -> v.code() + ": " + v.detail()).reduce((a, b) -> a + "\n" + b).orElse("");
    }

    /** 领域校验错误码。 */
    public static final class Codes {
        public static final String JSON_INVALID = "AI_JSON_INVALID";
        public static final String SCHEMA_INVALID = "AI_SCHEMA_INVALID";
        public static final String SCHEMA_VERSION_UNSUPPORTED = "AI_SCHEMA_VERSION_UNSUPPORTED";
        public static final String TASK_TYPE_INVALID = "AI_TASK_TYPE_INVALID";
        public static final String SUMMARY_MISSING = "AI_SUMMARY_MISSING";
        public static final String ESTIMATE_MISSING = "AI_ESTIMATE_MISSING";
        public static final String ESTIMATE_NOT_POSITIVE = "AI_ESTIMATE_NOT_POSITIVE";
        public static final String ESTIMATE_ORDER_INVALID = "AI_ESTIMATE_ORDER_INVALID";
        public static final String ESTIMATE_OVERSIZE = "AI_ESTIMATE_OVERSIZE";
        public static final String DECOMPOSITION_REQUIRED = "AI_DECOMPOSITION_REQUIRED";
        public static final String SKILL_UNKNOWN = "AI_SKILL_UNKNOWN";
        public static final String SKILL_LEVEL_INVALID = "AI_SKILL_LEVEL_INVALID";
        public static final String FORGED_CONFIRMATION = "AI_FORGED_CONFIRMATION";
        public static final String CONFIDENCE_OUT_OF_RANGE = "AI_CONFIDENCE_OUT_OF_RANGE";
        public static final String SUBTASK_LIMIT_EXCEEDED = "AI_SUBTASK_LIMIT_EXCEEDED";
        public static final String SUBTASK_KEY_INVALID = "AI_SUBTASK_KEY_INVALID";
        public static final String SUBTASK_FIELD_MISSING = "AI_SUBTASK_FIELD_MISSING";
        public static final String DEPENDENCY_SELF = "AI_DEPENDENCY_SELF";
        public static final String DEPENDENCY_UNKNOWN_NODE = "AI_DEPENDENCY_UNKNOWN_NODE";
        public static final String DEPENDENCY_CYCLE = "AI_DEPENDENCY_CYCLE";
        public static final String DECOMPOSITION_FLAG_CONFLICT = "AI_DECOMPOSITION_FLAG_CONFLICT";
        public static final String MISSING_INFO_LIMIT_EXCEEDED = "AI_MISSING_INFO_LIMIT_EXCEEDED";

        private Codes() {}
    }
}
