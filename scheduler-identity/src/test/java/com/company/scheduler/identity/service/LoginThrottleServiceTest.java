package com.company.scheduler.identity.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.company.scheduler.shared.error.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class LoginThrottleServiceTest {

    @SuppressWarnings("unchecked")
    private LoginThrottleService throttleWithIpLimit(int ipLimit) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(Object.class))).thenReturn(null);
        LoginSecurityProperties properties = new LoginSecurityProperties();
        properties.setIpMaxFailedAttempts(ipLimit);
        properties.setIpWindowMinutes(10);
        return new LoginThrottleService(jdbc, properties, Clock.fixed(Instant.now(), ZoneOffset.UTC));
    }

    @Test
    void ipWindowBlocksAfterLimitAndIsolatesOtherIps() {
        LoginThrottleService throttle = throttleWithIpLimit(3);
        for (int i = 0; i < 3; i++) {
            throttle.onFailure("someone", "10.0.0.1");
        }
        assertThatThrownBy(() -> throttle.checkAllowed("someone", "10.0.0.1"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThatCode(() -> throttle.checkAllowed("someone", "10.0.0.2")).doesNotThrowAnyException();
    }

    @Test
    void nullIpSkipsWindowCheck() {
        LoginThrottleService throttle = throttleWithIpLimit(1);
        throttle.onFailure("someone", null);
        assertThatCode(() -> throttle.checkAllowed("someone", null)).doesNotThrowAnyException();
    }
}
