package com.company.scheduler.identity.service;

import static org.assertj.core.api.Assertions.*;

import com.company.scheduler.shared.error.BusinessException;
import org.junit.jupiter.api.Test;

class PasswordPolicyTest {
    private final PasswordPolicy policy = new PasswordPolicy();

    @Test
    void acceptsReasonablePassword() {
        assertThatCode(() -> policy.validate("correct horse 123")).doesNotThrowAnyException();
        assertThatCode(() -> policy.validate("密码长度十二个字符以上")).doesNotThrowAnyException();
    }

    @Test
    void rejectsShortLongAndWhitespacePadded() {
        assertThatThrownBy(() -> policy.validate("short12345")).isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).code()).isEqualTo("INVALID_PASSWORD");
        assertThatThrownBy(() -> policy.validate("a".repeat(73))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> policy.validate(" padded-password ")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> policy.validate(null)).isInstanceOf(BusinessException.class);
    }
}
