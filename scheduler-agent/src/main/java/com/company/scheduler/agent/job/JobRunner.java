package com.company.scheduler.agent.job;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * 作业执行器。领取事务提交后才执行外部调用；执行期间周期性续租；
 * 结果写入全部带 (owner, lease_generation) fencing 条件，旧工作者无法覆盖回收后的新结果。
 */
@Component
public class JobRunner implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    private final AsyncJobRepository repository;
    private final RetryPolicy retryPolicy;
    private final Duration leaseDuration;
    private final String ownerIdentity;
    private final Map<String, JobHandler> handlersByType;
    private final ScheduledExecutorService leaseWatchdog;

    public JobRunner(AsyncJobRepository repository, RetryPolicy retryPolicy,
            AgentJobProperties properties, List<JobHandler> handlers) {
        this.repository = repository;
        this.retryPolicy = retryPolicy;
        this.leaseDuration = properties.leaseDuration();
        this.ownerIdentity = properties.workerIdentity();
        this.handlersByType = handlers.stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(JobHandler::jobType, Function.identity()));
        this.leaseWatchdog = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "agent-job-lease-watchdog");
            thread.setDaemon(true);
            return thread;
        });
    }

    public String ownerIdentity() {
        return ownerIdentity;
    }

    /** 回收过期租约并领取一个到期作业；领取事务提交后才返回，供线程池异步执行。 */
    public Optional<ClaimedJob> claim() {
        repository.reclaimExpired(Instant.now());
        return repository.claimNext(Instant.now(), ownerIdentity, Instant.now().plus(leaseDuration));
    }

    /**
     * 单次轮询：领取并同步执行。测试直接调用以获得确定性；生产由 JobPoller 派发到线程池。
     */
    public Optional<UUID> pollOnce() {
        return claim().map(this::executeSafely);
    }

    /** 供已持有租约的调用方（测试/管理端）执行；返回作业 id。 */
    public UUID executeSafely(ClaimedJob job) {
        JobHandler handler = handlersByType.get(job.jobType());
        if (handler == null) {
            markFailure(job, "UNKNOWN_JOB_TYPE:" + job.jobType(), true);
            return job.id();
        }
        ScheduledFuture<?> watchdog = startLeaseWatchdog(job);
        try {
            handler.handle(job);
            boolean accepted = repository.complete(job.id(), job.leaseOwner(), job.leaseGeneration(), "{}");
            if (!accepted) {
                log.warn("job {} completed but lease was lost or job canceled; result discarded", job.id());
            }
        } catch (PermanentJobFailure permanent) {
            markFailure(job, permanent.errorCode() + ":" + permanent.getMessage(), true);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            markFailure(job, "WORKER_INTERRUPTED", false);
        } catch (Exception failure) {
            log.warn("job {} attempt {} failed: {}", job.id(), job.attempts(), failure.getMessage());
            markFailure(job, failure.getClass().getSimpleName() + ":" + failure.getMessage(), false);
        } finally {
            watchdog.cancel(false);
        }
        return job.id();
    }

    private void markFailure(ClaimedJob job, String error, boolean permanent) {
        Instant now = Instant.now();
        Instant nextRunAt = permanent ? now : now.plus(retryPolicy.nextDelay(job.attempts(), now));
        Optional<AsyncJobStatus> updated = repository.markAttemptFailed(
                job.id(), job.leaseOwner(), job.leaseGeneration(), error, nextRunAt, permanent);
        if (updated.isEmpty()) {
            log.warn("job {} failure could not be recorded; lease lost or job canceled", job.id());
        } else if (updated.get() == AsyncJobStatus.FAILED) {
            log.error("job {} permanently failed after {} attempts: {}", job.id(), job.attempts(), error);
        }
    }

    /** 执行期间按租约周期的 1/3 续租；续租失败只记录，最终结果仍由 fencing 条件写入裁决。 */
    private ScheduledFuture<?> startLeaseWatchdog(ClaimedJob job) {
        long period = Math.max(1, leaseDuration.dividedBy(3).toSeconds());
        return leaseWatchdog.scheduleAtFixedRate(() -> {
            try {
                boolean renewed = repository.renewLease(job.id(), job.leaseOwner(),
                        job.leaseGeneration(), Instant.now().plus(leaseDuration));
                if (!renewed) {
                    log.warn("lease for job {} no longer valid; handler result will be fenced", job.id());
                }
            } catch (RuntimeException renewalError) {
                log.warn("lease renewal for job {} failed: {}", job.id(), renewalError.getMessage());
            }
        }, period, period, TimeUnit.SECONDS);
    }

    @Override
    public void destroy() {
        leaseWatchdog.shutdownNow();
    }
}
