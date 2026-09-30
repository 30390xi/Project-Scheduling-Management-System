package com.company.scheduler.agent.event;

import static org.assertj.core.api.Assertions.*;

import com.company.scheduler.agent.AgentIntegrationTestBase;
import com.company.scheduler.agent.notify.NotificationRepository;
import com.company.scheduler.agent.notify.NotificationService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/** 事务 Outbox、消费去重与站内通知唯一性（AC-24 场景）。 */
@Import(OutboxNotificationIT.ThrowingConsumerConfiguration.class)
class OutboxNotificationIT extends AgentIntegrationTestBase {

    @Autowired
    OutboxRepository outbox;
    @Autowired
    OutboxDispatcher dispatcher;
    @Autowired
    NotificationService notificationService;
    @Autowired
    NotificationRepository notifications;
    @Autowired
    org.springframework.transaction.support.TransactionTemplate transactions;
    @Autowired
    ThrowingConsumer throwingConsumer;

    @Test
    void outboxAppendRollsBackWithBusinessTransaction() {
        UUID newCompany = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        assertThatThrownBy(() -> transactions.execute(status -> {
            jdbc.getJdbcTemplate().update("INSERT INTO company(id) VALUES (?)", newCompany);
            outbox.append(eventId, newCompany, "demo.event", null, null, "{}");
            throw new IllegalStateException("业务回滚");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(outbox.countPending()).isEqualTo(0);
    }

    @Test
    void deliversOncePerRecipientAndDedupsRepeatDispatch() {
        UUID eventId = UUID.nameUUIDFromBytes(("evt-" + UUID.randomUUID()).getBytes());
        notificationService.publishInboxEvent(eventId, companyId,
                com.company.scheduler.agent.notify.Notification.Category.INFO,
                "派单已提交", "排期方案已提交，请查看。", List.of(memberId, secondMemberId),
                "SCHEDULE_PLAN", UUID.randomUUID());

        int dispatched = dispatcher.dispatchOnce(10);
        assertThat(dispatched).isEqualTo(1);
        assertThat(notifications.countByEvent(companyId, eventId)).isEqualTo(2);
        assertThat(outbox.countPending()).isZero();

        // 重复分发（模拟崩溃后重投）：processed_event 与通知唯一键双重去重
        dispatcher.dispatchOnce(10);
        assertThat(notifications.countByEvent(companyId, eventId)).isEqualTo(2);

        Integer inboxProcessed = jdbc.getJdbcTemplate().queryForObject(
                "SELECT count(*) FROM processed_event WHERE event_id = ? AND consumer_name = 'inbox-notification'",
                Integer.class, eventId);
        assertThat(inboxProcessed).as("站内消费者对该事件只处理一次").isEqualTo(1);

        var inbox = notifications.listByMember(companyId, memberId, 20);
        assertThat(inbox).hasSize(1);
        assertThat(inbox.get(0).title()).isEqualTo("派单已提交");
        assertThat(notifications.markRead(companyId, inbox.get(0).id(), memberId, java.time.Instant.now()))
                .isTrue();
        assertThat(notifications.markRead(companyId, inbox.get(0).id(), memberId, java.time.Instant.now()))
                .isFalse();
    }

    @Test
    void concurrentDispatchProducesSingleNotificationPerRecipient() throws Exception {
        UUID eventId = UUID.nameUUIDFromBytes(("race-" + UUID.randomUUID()).getBytes());
        notificationService.publishInboxEvent(eventId, companyId,
                com.company.scheduler.agent.notify.Notification.Category.HIGH_RISK,
                "容量冲突", "成员容量已满，请处理。", List.of(memberId), null, null);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Callable<Integer> dispatching = () -> {
                barrier.await(5, TimeUnit.SECONDS);
                return dispatcher.dispatchOnce(10);
            };
            Future<Integer> a = pool.submit(dispatching);
            Future<Integer> b = pool.submit(dispatching);
            a.get(10, TimeUnit.SECONDS);
            b.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(notifications.countByEvent(companyId, eventId))
                .as("两个分发器竞争同一事件，每个收件人只收到一条")
                .isEqualTo(1);
    }

    @Test
    void failingConsumerKeepsEventPendingAndRedeliversAfterRecovery() throws InterruptedException {
        UUID eventId = UUID.nameUUIDFromBytes(("flaky-" + UUID.randomUUID()).getBytes());
        throwingConsumer.failOnPurpose.set(true);
        notificationService.publishInboxEvent(eventId, companyId,
                com.company.scheduler.agent.notify.Notification.Category.INFO,
                "测试事件", "正文", List.of(memberId), null, null);

        dispatcher.dispatchOnce(10);
        // 站内消费者成功、抛错消费者失败：事件保持未发布，等待重投
        assertThat(outbox.countPending()).isEqualTo(1);
        assertThat(notifications.countByEvent(companyId, eventId)).isEqualTo(1);

        throwingConsumer.failOnPurpose.set(false);
        // 事件在重试窗口后重新可领取
        awaitPending(15);
        dispatcher.dispatchOnce(10);
        assertThat(outbox.countPending()).isZero();
        // 站内通知不因重投而重复
        assertThat(notifications.countByEvent(companyId, eventId)).isEqualTo(1);
        Integer processedThrowing = jdbc.getJdbcTemplate().queryForObject(
                "SELECT count(*) FROM processed_event WHERE event_id = ? AND consumer_name = 'throwing-test'",
                Integer.class, eventId);
        assertThat(processedThrowing).isEqualTo(1);
    }

    private void awaitPending(long timeoutSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000;
        while (System.currentTimeMillis() < deadline) {
            var next = jdbc.getJdbcTemplate().queryForObject(
                    "SELECT min(next_attempt_at) FROM outbox_event WHERE published_at IS NULL",
                    java.sql.Timestamp.class);
            if (next == null || !next.toInstant().isAfter(java.time.Instant.now())) {
                return;
            }
            Thread.sleep(100);
        }
    }

    /** 独立测试消费者：可按需抛错，验证消费者间互不影响与失败重投。 */
    static class ThrowingConsumer implements OutboxConsumer {
        final AtomicBoolean failOnPurpose = new AtomicBoolean(false);

        @Override
        public String consumerName() {
            return "throwing-test";
        }

        @Override
        public void consume(OutboxEvent event) {
            if (failOnPurpose.get()) {
                throw new IllegalStateException("模拟消费者故障");
            }
        }
    }

    @org.springframework.context.annotation.Configuration
    static class ThrowingConsumerConfiguration {
        @org.springframework.context.annotation.Bean
        ThrowingConsumer throwingConsumer() {
            return new ThrowingConsumer();
        }
    }
}
