package com.company.scheduler.identity.service;

import com.company.scheduler.shared.error.BusinessException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 密码规则：12～72 个 UTF-8 字节（BCrypt 上限），不允许空白环绕，不得与基础工程的开发种子账号混用。 */
@Component
public class PasswordPolicy {
    public static final int MIN_BYTES = 12;
    public static final int MAX_BYTES = 72;

    public void validate(String rawPassword) {
        if (rawPassword == null) {
            throw invalid("密码不能为空");
        }
        int bytes = rawPassword.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < MIN_BYTES) {
            throw invalid("密码至少需要 12 个字符");
        }
        if (bytes > MAX_BYTES) {
            throw invalid("密码不能超过 72 个 UTF-8 字节");
        }
        if (!rawPassword.equals(rawPassword.strip())) {
            throw invalid("密码首尾不能是空白字符");
        }
    }

    private BusinessException invalid(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD", message);
    }
}
