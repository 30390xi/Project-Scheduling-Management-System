package com.company.scheduler.agent.ai;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PromptSanitizerTest {

    private final PromptSanitizer sanitizer = new PromptSanitizer(200);

    @Test
    void redactsEmailsAndPhones() {
        String result = sanitizer.sanitize("联系 zhang.san@example.com 或 13812345678 了解需求");
        assertThat(result).doesNotContain("zhang.san@example.com").doesNotContain("13812345678");
        assertThat(result).contains("[REDACTED_EMAIL]").contains("[REDACTED_PHONE]");
    }

    @Test
    void stripsControlCharactersButKeepsNewlines() {
        String result = sanitizer.sanitize("标题\u0000\u0007正文\n第二段\t缩进");
        assertThat(result).isEqualTo("标题正文\n第二段\t缩进");
    }

    @Test
    void truncatesWithExplicitMarker() {
        String longText = "需".repeat(500);
        String result = sanitizer.sanitize(longText);
        assertThat(result.length()).isLessThanOrEqualTo(200 + 30);
        assertThat(result).contains("[TRUNCATED_AT_200_CHARS]");
    }

    @Test
    void emptyOrNullBecomesEmptyString() {
        assertThat(sanitizer.sanitize(null)).isEmpty();
        assertThat(sanitizer.sanitize("   ")).isEmpty();
    }

    @Test
    void redactionIsIdempotent() {
        String once = sanitizer.sanitize("邮箱 a@b.cc 电话 13900000000");
        assertThat(sanitizer.sanitize(once)).isEqualTo(once);
    }
}
