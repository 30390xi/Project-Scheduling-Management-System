package com.company.scheduler.identity.support;

import static org.assertj.core.api.Assertions.*;

import com.company.scheduler.shared.error.BusinessException;
import org.junit.jupiter.api.Test;

class IfMatchTest {
    @Test
    void parsesQuotedAndPlainVersions() {
        assertThat(IfMatch.require("\"3\"")).isEqualTo(3);
        assertThat(IfMatch.require("3")).isEqualTo(3);
        assertThat(IfMatch.require("W/\"12\"")).isEqualTo(12);
    }

    @Test
    void rejectsMissingOrMalformed() {
        assertThatThrownBy(() -> IfMatch.require(null)).isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).code()).isEqualTo("IF_MATCH_REQUIRED");
        assertThatThrownBy(() -> IfMatch.require("")).isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).code()).isEqualTo("IF_MATCH_REQUIRED");
        assertThatThrownBy(() -> IfMatch.require("abc")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> IfMatch.require("-1")).isInstanceOf(BusinessException.class);
    }
}
