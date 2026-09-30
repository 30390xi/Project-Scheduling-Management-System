package com.company.scheduler.agent.job;

import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 作业基础设施装配。enabled=false 时仍保留仓储、JobRunner 与 JobDispatcher（测试/管理端可直接驱动），
 * 只停用后台轮询。
 */
@Configuration
@EnableConfigurationProperties(AgentJobProperties.class)
public class AgentJobsConfiguration {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock systemClock() {
        return Clock.systemUTC();
    }

    @Bean
    RetryPolicy jobRetryPolicy(AgentJobProperties properties) {
        return new RetryPolicy(properties.retryBaseDelay(), properties.retryMaxDelay());
    }

    @Bean
    JobRunner jobRunner(AsyncJobRepository repository, RetryPolicy retryPolicy,
            AgentJobProperties properties, List<JobHandler> handlers) {
        return new JobRunner(repository, retryPolicy, properties, handlers);
    }

    /**
     * 后台轮询器：调度线程只负责领取，执行派发给受限线程池；
     * 线程池满时本轮跳过，下一轮再领（不超额领取）。
     */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(prefix = "scheduler.agent.jobs", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    static class JobPollingConfiguration {
        private static final Logger log = LoggerFactory.getLogger(JobPollingConfiguration.class);

        @Bean(destroyMethod = "shutdown")
        @Qualifier("agentJobWorkerPool")
        ExecutorService agentJobWorkerPool(AgentJobProperties properties) {
            return Executors.newFixedThreadPool(properties.workerThreads(), task -> {
                Thread thread = new Thread(task, "agent-job-worker");
                thread.setDaemon(true);
                return thread;
            });
        }

        @Bean
        JobPoller jobPoller(JobRunner runner, AgentJobProperties properties,
                @Qualifier("agentJobWorkerPool") ExecutorService workerPool) {
            return new JobPoller(runner, workerPool);
        }

        static class JobPoller {
            private static final Logger log = LoggerFactory.getLogger(JobPoller.class);
            private final JobRunner runner;
            private final ExecutorService workerPool;

            JobPoller(JobRunner runner, ExecutorService workerPool) {
                this.runner = runner;
                this.workerPool = workerPool;
            }

            @Scheduled(fixedDelayString = "${scheduler.agent.jobs.poll-interval-ms:2000}")
            void poll() {
                while (true) {
                    Optional<ClaimedJob> claimed;
                    try {
                        claimed = runner.claim();
                    } catch (RuntimeException pollError) {
                        log.warn("job poll failed: {}", pollError.getMessage());
                        return;
                    }
                    if (claimed.isEmpty()) {
                        return;
                    }
                    try {
                        workerPool.execute(() -> {
                            try {
                                runner.executeSafely(claimed.get());
                            } catch (RuntimeException executionError) {
                                log.error("job {} execution crashed: {}", claimed.get().id(),
                                        executionError.getMessage());
                            }
                        });
                    } catch (RejectedExecutionException poolFull) {
                        log.debug("worker pool full; job {} will be reclaimed after lease expiry",
                                claimed.get().id());
                        return;
                    }
                }
            }

            @PreDestroy
            void stop() {
                workerPool.shutdown();
                try {
                    if (!workerPool.awaitTermination(10, TimeUnit.SECONDS)) {
                        workerPool.shutdownNow();
                    }
                } catch (InterruptedException stopped) {
                    workerPool.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
