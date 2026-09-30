package com.company.scheduler.agent.ai;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class AiOutputValidatorTest {

    private final AiOutputValidator validator = new AiOutputValidator(AiGatewayProperties.defaults());

    @Test
    void acceptsValidSingleLeafSample() {
        var result = validator.validate(AiSamples.VALID_SINGLE_LEAF, AiSamples.SKILL_CATALOG);
        assertThat(result.valid()).as(result.describe()).isTrue();
        assertThat(result.output().taskType()).isEqualTo("BACKEND_DEVELOPMENT");
        assertThat(result.output().assumptions()).allMatch(a -> !Boolean.TRUE.equals(a.confirmed()));
    }

    @Test
    void acceptsValidDecompositionSampleWithAcyclicDependencies() {
        var result = validator.validate(AiSamples.VALID_DECOMPOSITION, AiSamples.SKILL_CATALOG);
        assertThat(result.valid()).as(result.describe()).isTrue();
        assertThat(result.output().subtasks()).hasSize(3);
        assertThat(result.output().dependencies()).hasSize(2);
    }

    @Test
    void rejectsIllegalJson() {
        var result = validator.validate("not-json at all", AiSamples.SKILL_CATALOG);
        assertThat(result.valid()).isFalse();
        assertThat(result.has(AiValidationResult.Codes.JSON_INVALID)).isTrue();
    }

    @Test
    void rejectsUnknownFields() {
        String withExtra = AiSamples.VALID_SINGLE_LEAF.replace("\"confidence\": 0.76",
                "\"confidence\": 0.76, \"secretRootAccess\": true");
        var result = validator.validate(withExtra, AiSamples.SKILL_CATALOG);
        assertThat(result.valid()).isFalse();
        assertThat(result.has(AiValidationResult.Codes.SCHEMA_INVALID)).isTrue();
    }

    @Test
    void rejectsSkillOutsideCatalog() {
        String unknownSkill = AiSamples.VALID_SINGLE_LEAF.replace("\"code\": \"JAVA\"", "\"code\": \"COBOL\"");
        var result = validator.validate(unknownSkill, AiSamples.SKILL_CATALOG);
        assertThat(result.valid()).isFalse();
        assertThat(result.has(AiValidationResult.Codes.SKILL_UNKNOWN)).isTrue();
    }

    @Test
    void rejectsModelConfirmedAssumption() {
        String forged = AiSamples.VALID_SINGLE_LEAF.replace("\"confirmed\": false", "\"confirmed\": true");
        var result = validator.validate(forged, AiSamples.SKILL_CATALOG);
        assertThat(result.valid()).isFalse();
        assertThat(result.has(AiValidationResult.Codes.FORGED_CONFIRMATION)).isTrue();
    }

    @Test
    void rejectsUnorderedOrNonPositiveEstimates() {
        String unordered = AiSamples.VALID_SINGLE_LEAF
                .replace("\"optimisticMinutes\": 180", "\"optimisticMinutes\": 300")
                .replace("\"likelyMinutes\": 240", "\"likelyMinutes\": 200");
        assertThat(validator.validate(unordered, AiSamples.SKILL_CATALOG)
                .has(AiValidationResult.Codes.ESTIMATE_ORDER_INVALID)).isTrue();

        String zero = AiSamples.VALID_SINGLE_LEAF.replace("\"optimisticMinutes\": 180", "\"optimisticMinutes\": 0");
        assertThat(validator.validate(zero, AiSamples.SKILL_CATALOG)
                .has(AiValidationResult.Codes.ESTIMATE_NOT_POSITIVE)).isTrue();
    }

    @Test
    void rejectsOversizeLeafWithoutDecomposition() {
        String oversize = AiSamples.VALID_SINGLE_LEAF
                .replace("\"pessimisticMinutes\": 360", "\"pessimisticMinutes\": 1200");
        var result = validator.validate(oversize, AiSamples.SKILL_CATALOG);
        assertThat(result.valid()).isFalse();
        assertThat(result.has(AiValidationResult.Codes.ESTIMATE_OVERSIZE)).isTrue();
    }

    @Test
    void rejectsDependencyCycle() {
        String cyclic = AiSamples.VALID_DECOMPOSITION
                .replace("{\"from\": \"S1\", \"to\": \"S3\", \"type\": \"FS\"},", "")
                .replace("{\"from\": \"S2\", \"to\": \"S3\", \"type\": \"FS\"}",
                        "{\"from\": \"S1\", \"to\": \"S2\", \"type\": \"FS\"}, {\"from\": \"S2\", \"to\": \"S1\", \"type\": \"FS\"}, {\"from\": \"S3\", \"to\": \"S3\", \"type\": \"FS\"}");
        var result = validator.validate(cyclic, AiSamples.SKILL_CATALOG);
        assertThat(result.valid()).isFalse();
        assertThat(result.has(AiValidationResult.Codes.DEPENDENCY_CYCLE)).isTrue();
        assertThat(result.has(AiValidationResult.Codes.DEPENDENCY_SELF)).isTrue();
    }

    @Test
    void rejectsDependencyToUnknownNode() {
        String unknownNode = AiSamples.VALID_DECOMPOSITION
                .replace("{\"from\": \"S1\", \"to\": \"S3\", \"type\": \"FS\"}",
                        "{\"from\": \"S1\", \"to\": \"S9\", \"type\": \"FS\"}");
        var result = validator.validate(unknownNode, AiSamples.SKILL_CATALOG);
        assertThat(result.valid()).isFalse();
        assertThat(result.has(AiValidationResult.Codes.DEPENDENCY_UNKNOWN_NODE)).isTrue();
    }

    @Test
    void rejectsDecompositionFlagConflictsAndSubtaskGaps() {
        String flagConflict = AiSamples.VALID_SINGLE_LEAF
                .replace("\"requiresDecomposition\": false", "\"requiresDecomposition\": true");
        assertThat(validator.validate(flagConflict, AiSamples.SKILL_CATALOG)
                .has(AiValidationResult.Codes.DECOMPOSITION_FLAG_CONFLICT)).isTrue();

        String missingAcceptance = AiSamples.VALID_DECOMPOSITION
                .replace("\"acceptanceCriteria\": [\"接口测试通过\"],", "");
        assertThat(validator.validate(missingAcceptance, AiSamples.SKILL_CATALOG)
                .has(AiValidationResult.Codes.SUBTASK_FIELD_MISSING)).isTrue();
    }

    @Test
    void rejectsMoreThanThirtySubtasksAndBadConfidence() {
        StringBuilder subtasks = new StringBuilder();
        for (int i = 1; i <= 31; i++) {
            if (i > 1) {
                subtasks.append(',');
            }
            subtasks.append("{\"clientKey\":\"S").append(i)
                    .append("\",\"title\":\"t\",\"scope\":\"s\",\"deliverables\":[\"d\"],")
                    .append("\"acceptanceCriteria\":[\"a\"],\"estimate\":{\"optimisticMinutes\":30,")
                    .append("\"likelyMinutes\":60,\"pessimisticMinutes\":90,\"basis\":\"b\"},")
                    .append("\"requiredSkills\":[]}");
        }
        String oversized = """
                {"schemaVersion":"1.0","taskType":"OTHER","summary":"s","requiresDecomposition":true,
                 "estimate":{"optimisticMinutes":930,"likelyMinutes":1860,"pessimisticMinutes":2790,"basis":"b"},
                 "requiredSkills":[],"acceptanceCriteria":["a"],"assumptions":[],"missingInformation":[],
                 "confidence":0.5,"subtasks":[%s],"dependencies":[]}
                """.formatted(subtasks);
        var result = validator.validate(oversized, AiSamples.SKILL_CATALOG);
        assertThat(result.valid()).isFalse();
        assertThat(result.has(AiValidationResult.Codes.SUBTASK_LIMIT_EXCEEDED)).isTrue();

        String badConfidence = AiSamples.VALID_SINGLE_LEAF.replace("\"confidence\": 0.76", "\"confidence\": 1.4");
        assertThat(validator.validate(badConfidence, AiSamples.SKILL_CATALOG)
                .has(AiValidationResult.Codes.CONFIDENCE_OUT_OF_RANGE)).isTrue();
    }

    @Test
    void rejectsUnsupportedSchemaVersionAndTaskType() {
        String wrongVersion = AiSamples.VALID_SINGLE_LEAF.replace("\"1.0\"", "\"0.9\"");
        assertThat(validator.validate(wrongVersion, AiSamples.SKILL_CATALOG)
                .has(AiValidationResult.Codes.SCHEMA_VERSION_UNSUPPORTED)).isTrue();

        String lowercaseType = AiSamples.VALID_SINGLE_LEAF.replace("BACKEND_DEVELOPMENT", "backend_dev");
        assertThat(validator.validate(lowercaseType, AiSamples.SKILL_CATALOG)
                .has(AiValidationResult.Codes.TASK_TYPE_INVALID)).isTrue();
    }

    @Test
    void fixedInstantUsedForDeterministicJitterIsStable() {
        Instant fixed = LocalDate.of(2026, 9, 30).atStartOfDay(ZoneOffset.UTC).toInstant();
        var policy = new com.company.scheduler.agent.job.RetryPolicy(
                java.time.Duration.ofSeconds(10), java.time.Duration.ofMinutes(10));
        assertThat(policy.nextDelay(1, fixed)).isEqualTo(policy.nextDelay(1, fixed));
        assertThat(policy.nextDelay(3, fixed)).isGreaterThanOrEqualTo(policy.nextDelay(1, fixed));
    }
}
