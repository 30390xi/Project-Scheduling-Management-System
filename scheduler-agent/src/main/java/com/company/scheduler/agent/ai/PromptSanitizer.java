package com.company.scheduler.agent.ai;

import java.util.regex.Pattern;

/**
 * 发送给模型前的输入最小化与脱敏：
 * 去控制字符、剔除邮箱/电话（模型不需要成员通讯录）、按上限截断。
 * 纯函数，便于固定样本测试。
 */
public final class PromptSanitizer {

    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final String EMAIL_PLACEHOLDER = "[REDACTED_EMAIL]";
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(\\+?\\d[\\d\\s-]{6,18}\\d)(?!\\d)");
    private static final String PHONE_PLACEHOLDER = "[REDACTED_PHONE]";

    private final int maxChars;

    public PromptSanitizer(int maxChars) {
        if (maxChars < 200) {
            throw new IllegalArgumentException("maxChars must be at least 200");
        }
        this.maxChars = maxChars;
    }

    public String sanitize(String taskText) {
        if (taskText == null || taskText.isBlank()) {
            return "";
        }
        String cleaned = CONTROL_CHARS.matcher(taskText).replaceAll("");
        cleaned = EMAIL.matcher(cleaned).replaceAll(EMAIL_PLACEHOLDER);
        cleaned = PHONE.matcher(cleaned).replaceAll(PHONE_PLACEHOLDER);
        if (cleaned.length() > maxChars) {
            return cleaned.substring(0, maxChars) + "\n[TRUNCATED_AT_" + maxChars + "_CHARS]";
        }
        return cleaned;
    }
}
