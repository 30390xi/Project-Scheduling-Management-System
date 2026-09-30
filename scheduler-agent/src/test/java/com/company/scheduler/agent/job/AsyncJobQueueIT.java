package com.company.scheduler.agent.job;

import static org.assertj.core.api.Assertions.*;

import com.company.scheduler.agent.AgentIntegrationTestBase;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/** 作业队列核心语义：领取竞争、租约 fencing、过期回收、重试、幂等与取消。 */
@Import(AsyncJobQueueIT.FlakyHandlerConfiguration.class)
class AsyncJobQueueIT extends AgentIntegrationTestBase {

    @Autowired
    AsyncJobRepository repository;
    @Autowired
    ReliableJobDispatcher dispatcher;
    @Autowired
    JobRunner runner;
    @Autowired
    FlakyTestHandler flakyHandler;
    @Autowired
    org.springframework.transaction.support.TransactionTemplate transactions;

    @Test
    void enqueueIsIdempotentPerCompanyTypeAndKey() {
        var first = repository.enqueue(companyId, "TEST_KIND", null, 0, "key-1", "{}", 3);
        var second = repository.enqueue(companyId, "TEST_KIND", null, 0, "key-1", "{}", 3);
        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.jobId()).isEqualTo(first.jobId());
        assertThat(repository.findByKey(companyId, "TEST_KIND", "key-1")).isPresent();

        // 不存在的公司被外键拒绝，绝不产生无归属作业
        assertThatThrownBy(() -> repository.enqueue(UUID.randomUUID(), "TEST_KIND", null, 0,
                "key-1", "{}", 3)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void concurrentClaimsHaveExactlyOneWinner() throws Exception {
        repository.enqueue(companyId, "TEST_KIND", null, 0, "race-key", "{}", 3);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Callable<Optional<ClaimedJob>> claimer = () -> {
                barrier.await(5, TimeUnit.SECONDS);
                return repository.claimNext(Instant.now(), "claimer-" + Thread.currentThread().getName(),
                        Instant.now().plusSeconds(60));
            };
            Future<Optional<ClaimedJob>> a = pool.submit(claimer);
            Future<Optional<ClaimedJob>> b = pool.submit(claimer);
            long winners = List.of(a.get(), b.get()).stream().filter(Optional::isPresent).count();
            assertThat(winners).as("两个实例同时领取同一作业，只允许一个持有效租约").isOne();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void expiredLeaseIsReclaimedAndOldWorkerCannotOverwrite() {
        var enqueued = repository.enqueue(companyId, "TEST_KIND", null, 0, "lease-key", "{}", 3);
        Instant now = Instant.now();

        ClaimedJob stale = repository.claimNext(now, "worker-a", now.plusSeconds(1)).orElseThrow();
        assertThat(stale.leaseGeneration()).isEqualTo(1);

        // 租约过期回收，第二工作者领取，fencing generation 递增
        repository.reclaimExpired(now.plusSeconds(2));
        ClaimedJob fresh = repository.claimNext(now.plusSeconds(2), "worker-b",
                now.plusSeconds(300)).orElseThrow();
        assertThat(fresh.leaseGeneration()).isEqualTo(2);

        // 旧工作者（generation=1）写结果被拒绝；新工作者成功
        assertThat(repository.complete(stale.id(), "worker-a", 1, "{\"stale\":true}")).isFalse();
        assertThat(repository.complete(fresh.id(), "worker-b", 2, "{\"fresh\":true}")).isTrue();

        var job = repository.findById(enqueued.jobId()).orElseThrow();
        assertThat(job.status()).isEqualTo(AsyncJobStatus.SUCCEEDED);
        assertThat(job.resultJson()).contains("fresh");
        assertThat(job.leaseGeneration()).isEqualTo(2);
    }

    @Test
    void failedAttemptsRetryWithBackoffThenFailPermanently() throws Exception {
        var retried = repository.enqueue(companyId, "TEST_KIND", null, 0, "retry-key", "{}", 3);
        flakyHandler.failNextCalls(2);
        runner.pollOnce();
        assertThat(repository.findById(retried.jobId()).orElseThrow().status())
                .isEqualTo(AsyncJobStatus.RETRY_WAIT);
        // 退避默认 100ms 基数；等待重新可领取
        awaitEligible(retried.jobId());
        runner.pollOnce();
        assertThat(repository.findById(retried.jobId()).orElseThrow().status())
                .isEqualTo(AsyncJobStatus.RETRY_WAIT);
        awaitEligible(retried.jobId());
        runner.pollOnce();
        assertThat(flakyHandler.successCount.get()).isEqualTo(1);
        assertThat(repository.findById(retried.jobId()).orElseThrow().status())
                .isEqualTo(AsyncJobStatus.SUCCEEDED);

        var exhausted = repository.enqueue(companyId, "TEST_KIND", null, 0, "exhaust-key", "{}", 2);
        flakyHandler.failNextCalls(99);
        runner.pollOnce();
        awaitEligible(exhausted.jobId());
        runner.pollOnce();
        var job = repository.findById(exhausted.jobId()).orElseThrow();
        assertThat(job.status()).isEqualTo(AsyncJobStatus.FAILED);
        assertThat(job.attempts()).isEqualTo(2);
        assertThat(job.lastError()).contains("FlakyTestHandler");
    }

    @Test
    void canceledJobsAreNotClaimedAndInflightResultsAreFenced() {
        var queued = repository.enqueue(companyId, "TEST_KIND", null, 0, "cancel-queued", "{}", 3);
        assertThat(repository.cancel(companyId, queued.jobId())).isTrue();
        assertThat(repository.findById(queued.jobId()).orElseThrow().status())
                .isEqualTo(AsyncJobStatus.CANCELED);
        assertThat(repository.claimNext(Instant.now(), "worker-x", Instant.now().plusSeconds(60)))
                .isEmpty();
        // 重复取消与未知作业返回 false，不报错
        assertThat(repository.cancel(companyId, queued.jobId())).isFalse();

        var running = repository.enqueue(companyId, "TEST_KIND", null, 0, "cancel-running", "{}", 3);
        ClaimedJob claimed = repository.claimNext(Instant.now(), "worker-y",
                Instant.now().plusSeconds(60)).orElseThrow();
        assertThat(repository.cancel(companyId, running.jobId())).isTrue();
        assertThat(repository.complete(claimed.id(), "worker-y", claimed.leaseGeneration(),
                "{\"late\":true}")).isFalse();
    }

    @Test
    void dispatcherEnqueueJoinsCallerBusinessTransaction() {
        UUID newCompany = UUID.randomUUID();
        assertThatThrownBy(() -> transactions.execute(status -> {
            jdbc.getJdbcTemplate().update("INSERT INTO company(id) VALUES (?)", newCompany);
            dispatcher.enqueue(newCompany, "TEST_KIND", null, 0, "rollback-key");
            throw new IllegalStateException("业务写入失败，整体回滚");
        })).isInstanceOf(IllegalStateException.class);
        // 作业随业务回滚消失：不存在“业务失败但作业已入队”的中间状态
        assertThat(repository.findByKey(newCompany, "TEST_KIND", "rollback-key")).isEmpty();
        Long companies = jdbc.getJdbcTemplate()
                .queryForObject("SELECT count(*) FROM company WHERE id = ?", Long.class, newCompany);
        assertThat(companies).isZero();
    }

    @Test
    void unknownJobTypeFailsPermanentlyInsteadOfSilentlySucceeding() {
        var enqueued = repository.enqueue(companyId, "NO_SUCH_TYPE", null, 0, "unknown-key", "{}", 3);
        runner.pollOnce();
        var job = repository.findById(enqueued.jobId()).orElseThrow();
        assertThat(job.status()).isEqualTo(AsyncJobStatus.FAILED);
        assertThat(job.lastError()).contains("UNKNOWN_JOB_TYPE");
    }

    private void awaitEligible(UUID jobId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            var job = repository.findById(jobId).orElseThrow();
            if (!job.nextRunAt().isAfter(Instant.now())) {
                return;
            }
            Thread.sleep(50);
        }
        fail("job did not become eligible for retry in time");
    }

    /** 测试专用不稳定处理器：先失败 N 次再成功。 */
    static class FlakyTestHandler implements JobHandler {
        final AtomicInteger failuresToSimulate = new AtomicInteger();
        final AtomicInteger successCount = new AtomicInteger();

        void failNextCalls(int count) {
            failuresToSimulate.set(count);
        }

        @Override
        public String jobType() {
            return "TEST_KIND";
        }

        @Override
        public void handle(ClaimedJob job) {
            if (failuresToSimulate.getAndDecrement() > 0) {
                throw new IllegalStateException("FlakyTestHandler 模拟瞬时失败");
            }
            successCount.incrementAndGet();
        }
    }

    @org.springframework.context.annotation.Configuration
    static class FlakyHandlerConfiguration {
        @org.springframework.context.annotation.Bean
        FlakyTestHandler flakyTestHandler() {
            return new FlakyTestHandler();
        }
    }
}
