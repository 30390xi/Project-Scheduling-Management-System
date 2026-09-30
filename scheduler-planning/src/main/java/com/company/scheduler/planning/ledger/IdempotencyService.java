package com.company.scheduler.planning.ledger;

import com.company.scheduler.shared.error.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 排期写接口幂等记录。begin 必须在公司 guard 行锁之后调用（TRD 9.2）。
 * 同 key 同内容：业务成功后重放返回首次结果；同 key 不同内容：409。
 * FAILED 记录允许同内容重试；PROCESSING 记录视为并发进行中。
 */
@Service
public class IdempotencyService {
    public enum Outcome { NEW, REPLAYED }

    public record Begin(Outcome outcome, JsonNode replayedResponse) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public IdempotencyService(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public static String requestHash(String canonicalRequest) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public Begin begin(UUID companyId, UUID actorId, String route, String key, String requestHash) {
        requireActiveTransaction();
        int inserted = jdbc.update("""
                INSERT INTO planning_idempotency(company_id, actor_id, route, idem_key, request_hash, status)
                VALUES (:companyId, :actorId, :route, :key, :hash, 'PROCESSING')
                ON CONFLICT DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("companyId", companyId)
                .addValue("actorId", actorId)
                .addValue("route", route)
                .addValue("key", key)
                .addValue("hash", requestHash));
        if (inserted == 1) {
            return new Begin(Outcome.NEW, null);
        }
        var rows = jdbc.query("""
                SELECT request_hash, status, response_snapshot FROM planning_idempotency
                 WHERE company_id = :companyId AND actor_id = :actorId AND route = :route AND idem_key = :key
                """, new MapSqlParameterSource()
                .addValue("companyId", companyId)
                .addValue("actorId", actorId)
                .addValue("route", route)
                .addValue("key", key), (rs, i) -> new Object[] {
                        rs.getString("request_hash"), rs.getString("status"), rs.getString("response_snapshot")});
        var row = rows.stream().findFirst().orElseThrow(() ->
                new IllegalStateException("Idempotency row missing after conflict"));
        if (!requestHash.equals(row[0])) {
            throw new BusinessException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                    "幂等键已被不同请求使用");
        }
        switch ((String) row[1]) {
            case "SUCCEEDED" -> {
                return new Begin(Outcome.REPLAYED, parse((String) row[2]));
            }
            case "FAILED" -> {
                int reset = jdbc.update("""
                        UPDATE planning_idempotency
                           SET status = 'PROCESSING', request_hash = :hash, updated_at = now()
                         WHERE company_id = :companyId AND actor_id = :actorId AND route = :route
                           AND idem_key = :key AND status = 'FAILED'
                        """, new MapSqlParameterSource()
                        .addValue("companyId", companyId)
                        .addValue("actorId", actorId)
                        .addValue("route", route)
                        .addValue("key", key)
                        .addValue("hash", requestHash));
                if (reset == 1) {
                    return new Begin(Outcome.NEW, null);
                }
                throw concurrent();
            }
            default -> throw concurrent();
        }
    }

    public void succeed(UUID companyId, UUID actorId, String route, String key, JsonNode responseSnapshot) {
        int updated = jdbc.update("""
                UPDATE planning_idempotency
                   SET status = 'SUCCEEDED', response_snapshot = CAST(:snapshot AS jsonb), updated_at = now()
                 WHERE company_id = :companyId AND actor_id = :actorId AND route = :route
                   AND idem_key = :key AND status = 'PROCESSING'
                """, new MapSqlParameterSource()
                .addValue("snapshot", toJson(responseSnapshot))
                .addValue("companyId", companyId)
                .addValue("actorId", actorId)
                .addValue("route", route)
                .addValue("key", key));
        if (updated != 1) {
            throw new IllegalStateException("Idempotency record not in PROCESSING state on success");
        }
    }

    /** Marks a failed attempt so the same key can retry with the same content. */
    public void fail(UUID companyId, UUID actorId, String route, String key) {
        jdbc.update("""
                UPDATE planning_idempotency SET status = 'FAILED', updated_at = now()
                 WHERE company_id = :companyId AND actor_id = :actorId AND route = :route
                   AND idem_key = :key AND status = 'PROCESSING'
                """, new MapSqlParameterSource()
                .addValue("companyId", companyId)
                .addValue("actorId", actorId)
                .addValue("route", route)
                .addValue("key", key));
    }

    private BusinessException concurrent() {
        return new BusinessException(HttpStatus.CONFLICT, "IDEMPOTENCY_IN_PROGRESS",
                "相同幂等键的请求正在处理，请稍后重试");
    }

    private JsonNode parse(String json) {
        if (json == null) {
            throw new IllegalStateException("SUCCEEDED idempotency record without response snapshot");
        }
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("Corrupt idempotency response snapshot", e);
        }
    }

    private String toJson(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize idempotency snapshot", e);
        }
    }

    private static void requireActiveTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Idempotency writes require an active transaction");
        }
    }
}
