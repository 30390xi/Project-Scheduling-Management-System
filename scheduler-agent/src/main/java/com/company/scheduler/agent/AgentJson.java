package com.company.scheduler.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.company.scheduler.shared.error.BusinessException;
import org.springframework.http.HttpStatus;

/**
 * 模块内部专用 JSON 工具：不依赖应用全局 ObjectMapper 配置，
 * 读写都拒绝未知字段，保证模型输出与事件载荷的严格契约。
 */
public final class AgentJson {

    private static final ObjectMapper STRICT = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    private AgentJson() {}

    public static String toJson(Object value) {
        try {
            return STRICT.writeValueAsString(value);
        } catch (JsonProcessingException processingError) {
            throw new IllegalArgumentException("object is not serializable: " + processingError.getMessage());
        }
    }

    /** 严格解析：非法 JSON、未知字段都抛 BusinessException(AI_JSON_INVALID)，供领域校验归类。 */
    public static <T> T fromJson(String json, Class<T> type) {
        try {
            return STRICT.readValue(json, type);
        } catch (JsonProcessingException processingError) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "AI_JSON_INVALID",
                    "JSON 解析失败: " + processingError.getOriginalMessage());
        }
    }

    /** 解析任意 JSON 文档为树；非法 JSON 抛 IllegalArgumentException。 */
    public static com.fasterxml.jackson.databind.JsonNode toJsonNode(String json) {
        try {
            return STRICT.readTree(json);
        } catch (JsonProcessingException processingError) {
            throw new IllegalArgumentException("invalid JSON: " + processingError.getOriginalMessage());
        }
    }
}
