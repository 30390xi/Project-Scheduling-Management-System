package com.company.scheduler.identity.support;

import com.company.scheduler.shared.error.BusinessException;
import org.springframework.http.HttpStatus;

/** TRD 11.1：更新实体必须携带 If-Match: "<version>"，缺失 400、格式错误 400、版本不符 409。 */
public final class IfMatch {
    private IfMatch() {}

    public static long require(String header) {
        if (header == null || header.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "IF_MATCH_REQUIRED",
                    "更新操作必须携带 If-Match 头并填写当前版本号");
        }
        String value = header.trim();
        if (value.startsWith("W/")) value = value.substring(2).trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        try {
            long version = Long.parseLong(value);
            if (version < 0) throw new NumberFormatException("negative");
            return version;
        } catch (NumberFormatException ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "If-Match 版本号格式错误");
        }
    }
}
