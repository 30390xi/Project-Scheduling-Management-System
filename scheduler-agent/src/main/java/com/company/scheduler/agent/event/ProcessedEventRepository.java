package com.company.scheduler.agent.event;

import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** (consumer_name, event_id) 消费去重。必须与消费者副作用写入处于同一事务。 */
@Repository
public class ProcessedEventRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ProcessedEventRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true 表示本事务首次占用该事件（应执行消费）；false 表示已消费过，直接跳过。 */
    public boolean tryBegin(String consumerName, UUID eventId, UUID companyId) {
        return jdbc.update("""
                INSERT INTO processed_event(consumer_name, event_id, company_id)
                VALUES (:consumer, :eventId, :companyId)
                ON CONFLICT (consumer_name, event_id) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("consumer", consumerName)
                .addValue("eventId", eventId)
                .addValue("companyId", companyId)) == 1;
    }
}
