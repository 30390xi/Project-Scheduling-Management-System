package com.company.scheduler.planning.support;

import com.company.scheduler.shared.error.BusinessException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Company-scoped clock interpretation: planning dates follow the company timezone. */
@Repository
public class CompanyRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public CompanyRepository(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public ZoneId timezone(UUID companyId) {
        var rows = jdbc.query("SELECT timezone FROM company WHERE id = :id",
                new MapSqlParameterSource("id", companyId), (rs, i) -> rs.getString("timezone"));
        String timezone = rows.stream().findFirst().orElseThrow(BusinessException::notFound);
        try {
            return ZoneId.of(timezone);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Company has an invalid timezone: " + companyId, e);
        }
    }

    /** Today in the company timezone; the boundary for past-date and snapshot rules. */
    public LocalDate today(UUID companyId) {
        return LocalDate.now(clock.withZone(timezone(companyId)));
    }
}
