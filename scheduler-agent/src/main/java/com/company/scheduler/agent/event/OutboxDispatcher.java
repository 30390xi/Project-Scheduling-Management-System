package com.company.scheduler.agent.event;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Outbox 分发器：至少一次语义。
 * 每个事件 × 每个消费者一个短事务：先 processed_event 占用，占用成功才执行 consume，
 * 同事务提交；崩溃后重投由 (consumer_name,event_id) 与通知唯一键去重。
 */
@Component
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxRepository outbox;
    private final ProcessedEventRepository processedEvents;
    private final List<OutboxConsumer> consumers;
    private final TransactionTemplate transactions;
    private final Duration retryDelay;

    public OutboxDispatcher(OutboxRepository outbox, ProcessedEventRepository processedEvents,
            List<OutboxConsumer> consumers, TransactionTemplate transactions,
            @org.springframework.beans.factory.annotation.Value(
                    "${scheduler.agent.outbox.retry-delay-ms:30000}") long retryDelayMillis) {
        this.outbox = outbox;
        this.processedEvents = processedEvents;
        this.consumers = List.copyOf(consumers);
        this.transactions = transactions;
        this.retryDelay = Duration.ofMillis(retryDelayMillis);
    }

    /** 处理一批待发布事件。返回处理的批次数；测试直接调用以获得确定性。 */
    public int dispatchOnce(int batchSize) {
        Instant now = Instant.now();
        List<OutboxEvent> batch = outbox.claimBatch(now, now.plus(retryDelay), batchSize);
        for (OutboxEvent event : batch) {
            dispatchSingle(event);
        }
        return batch.size();
    }

    private void dispatchSingle(OutboxEvent event) {
        boolean allConsumersSucceeded = true;
        for (OutboxConsumer consumer : consumers) {
            try {
                transactions.executeWithoutResult(status -> {
                    if (processedEvents.tryBegin(consumer.consumerName(), event.id(), event.companyId())) {
                        consumer.consume(event);
                    }
                });
            } catch (RuntimeException consumeError) {
                // processed_event 占用随事务回滚，稍后重投整体重试
                allConsumersSucceeded = false;
                log.warn("consumer {} failed on event {}: {}", consumer.consumerName(), event.id(),
                        consumeError.getMessage());
            }
        }
        if (allConsumersSucceeded) {
            outbox.markPublished(event.id());
        } else {
            outbox.scheduleRetry(event.id(), Instant.now().plus(retryDelay));
        }
    }

    @Scheduled(fixedDelayString = "${scheduler.agent.outbox.poll-interval-ms:2000}")
    void poll() {
        dispatchOnce(50);
    }
}
