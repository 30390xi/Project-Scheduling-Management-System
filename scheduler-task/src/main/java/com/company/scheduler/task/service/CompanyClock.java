package com.company.scheduler.task.service;

import com.company.scheduler.shared.error.BusinessException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 公司时区与日期口径：排期日期按公司 ZoneId 解释。 */
@Service
public class CompanyClock {
    private final JdbcTemplate jdbc;

    public CompanyClock(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public ZoneId zoneOf(UUID companyId) {
        String zone = jdbc.query("SELECT timezone FROM company WHERE id = ?",
                rs -> rs.next() ? rs.getString("timezone") : null, companyId);
        if (zone == null) throw BusinessException.notFound();
        return ZoneId.of(zone);
    }

    public LocalDate today(UUID companyId) {
        return LocalDate.now(zoneOf(companyId));
    }

    public void requireNotPast(UUID companyId, LocalDate date, String fieldName) {
        if (date.isBefore(today(companyId))) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                    fieldName + " 不能早于公司今日");
        }
    }
}
