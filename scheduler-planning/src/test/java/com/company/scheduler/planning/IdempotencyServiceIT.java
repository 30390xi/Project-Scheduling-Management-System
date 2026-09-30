package com.company.scheduler.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.scheduler.planning.ledger.IdempotencyService;
import com.company.scheduler.shared.error.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 幂等记录语义：同键同内容重放、不同内容 409、失败可重试、并发只有一个业务执行。 */
class IdempotencyServiceIT extends PlanningPostgresIT {

    private final ObjectMapper mapper = new ObjectMapper();

    private record Keys(UUID company, UUID actor, String route, String key, String hashA, String hashB) {}

    private Keys keys() {
        UUID company = newCompany();
        UUID actor = newMember(company, "MEMBER");
        return new Keys(company, actor, "test/commit", "idem-" + UUID.randomUUID(),
                IdempotencyService.requestHash("{\"a\":1}"), IdempotencyService.requestHash("{\"a\":2}"));
    }

    @Test
    void replaysStoredResponseForSameKeyAndHash() throws Exception {
        var k = keys();
        var service = idempotencyService();
        var snapshot = mapper.readTree("{\"planId\":\"33333333-3333-4333-8333-333333333333\",\"revision\":7}");

        var first = tx.execute(status -> service.begin(k.company(), k.actor(), k.route(), k.key(), k.hashA()));
        assertThat(first.outcome()).isEqualTo(IdempotencyService.Outcome.NEW);
        tx.executeWithoutResult(status ->
                service.succeed(k.company(), k.actor(), k.route(), k.key(), snapshot));

        var replay = tx.execute(status -> service.begin(k.company(), k.actor(), k.route(), k.key(), k.hashA()));
        assertThat(replay.outcome()).isEqualTo(IdempotencyService.Outcome.REPLAYED);
        assertThat(replay.replayedResponse()).isEqualTo(snapshot);

        assertThatThrownBy(() -> tx.execute(status ->
                service.begin(k.company(), k.actor(), k.route(), k.key(), k.hashB())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void scopesKeysByActorAndRoute() throws Exception {
        var k = keys();
        UUID otherActor = newMember(k.company(), "MEMBER");
        var service = idempotencyService();
        var snapshot = mapper.readTree("{\"ok\":true}");

        tx.executeWithoutResult(status -> {
            service.begin(k.company(), k.actor(), k.route(), k.key(), k.hashA());
            service.succeed(k.company(), k.actor(), k.route(), k.key(), snapshot);
        });
        var other = tx.execute(status -> service.begin(k.company(), otherActor, k.route(), k.key(), k.hashA()));
        assertThat(other.outcome()).isEqualTo(IdempotencyService.Outcome.NEW);
        var otherRoute = tx.execute(status -> service.begin(k.company(), k.actor(), "other/route", k.key(), k.hashA()));
        assertThat(otherRoute.outcome()).isEqualTo(IdempotencyService.Outcome.NEW);
    }

    @Test
    void failedAttemptAllowsRetryWithSameContent() throws Exception {
        var k = keys();
        var service = idempotencyService();

        var first = tx.execute(status -> service.begin(k.company(), k.actor(), k.route(), k.key(), k.hashA()));
        assertThat(first.outcome()).isEqualTo(IdempotencyService.Outcome.NEW);
        tx.executeWithoutResult(status -> service.fail(k.company(), k.actor(), k.route(), k.key()));

        var retrySnapshot = mapper.readTree("{\"x\":1}");
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                service.succeed(k.company(), k.actor(), k.route(), k.key(), retrySnapshot)))
                .isInstanceOf(IllegalStateException.class);

        var retry = tx.execute(status -> service.begin(k.company(), k.actor(), k.route(), k.key(), k.hashA()));
        assertThat(retry.outcome()).isEqualTo(IdempotencyService.Outcome.NEW);
    }

    @Test
    void processingRecordSignalsConcurrentRequest() throws Exception {
        var k = keys();
        var service = idempotencyService();
        jdbc.update("""
                INSERT INTO planning_idempotency(company_id, actor_id, route, idem_key, request_hash, status)
                VALUES (?, ?, ?, ?, ?, 'PROCESSING')
                """, k.company(), k.actor(), k.route(), k.key(), k.hashA());

        assertThatThrownBy(() -> tx.execute(status ->
                service.begin(k.company(), k.actor(), k.route(), k.key(), k.hashA())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code()).isEqualTo("IDEMPOTENCY_IN_PROGRESS");
    }

    @Test
    void concurrentSameKeyTransactionsProduceOneBusinessExecution() throws Exception {
        var k = keys();
        var service = idempotencyService();
        var snapshot = mapper.readTree("{\"planId\":\"p\",\"revision\":1}");
        var barrier = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var tasks = java.util.stream.IntStream.range(0, 2).mapToObj(i ->
                    (Callable<IdempotencyService.Begin>) () -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        return tx.execute(status -> {
                            guardRepository().lockGuard(k.company());
                            var begin = service.begin(k.company(), k.actor(), k.route(), k.key(), k.hashA());
                            if (begin.outcome() == IdempotencyService.Outcome.NEW) {
                                service.succeed(k.company(), k.actor(), k.route(), k.key(), snapshot);
                            }
                            return begin;
                        });
                    }).toList();
            int newCount = 0;
            int replayedCount = 0;
            for (Future<IdempotencyService.Begin> future : pool.invokeAll(tasks)) {
                var begin = future.get(30, TimeUnit.SECONDS);
                if (begin.outcome() == IdempotencyService.Outcome.NEW) newCount++;
                if (begin.outcome() == IdempotencyService.Outcome.REPLAYED) replayedCount++;
            }
            assertThat(newCount).isEqualTo(1);
            assertThat(replayedCount).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        Integer succeeded = jdbc.queryForObject("""
                SELECT count(*) FROM planning_idempotency
                 WHERE company_id = ? AND status = 'SUCCEEDED'
                """, Integer.class, k.company());
        assertThat(succeeded).isEqualTo(1);
    }
}
