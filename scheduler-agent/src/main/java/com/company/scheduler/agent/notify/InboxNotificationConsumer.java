package com.company.scheduler.agent.notify;

import com.company.scheduler.agent.AgentJson;
import com.company.scheduler.agent.event.OutboxConsumer;
import com.company.scheduler.agent.event.OutboxEvent;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * notification.inbox 事件消费者：在 processed_event 占用成功的事务内，
 * 把事件扇出为每个收件人一条站内通知；唯一键兜底，重复消费不产生重复消息。
 */
@Component
public class InboxNotificationConsumer implements OutboxConsumer {

    public static final String CONSUMER_NAME = "inbox-notification";

    private static final Logger log = LoggerFactory.getLogger(InboxNotificationConsumer.class);

    private final NotificationRepository notifications;

    public InboxNotificationConsumer(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    @Override
    public String consumerName() {
        return CONSUMER_NAME;
    }

    @Override
    public void consume(OutboxEvent event) {
        if (!NotificationService.EVENT_TYPE.equals(event.eventType())) {
            return;
        }
        JsonNode payload = AgentJson.fromJson(event.payloadJson(), JsonNode.class);
        JsonNode recipients = payload.path("recipientMemberIds");
        if (!recipients.isArray() || recipients.isEmpty()) {
            log.warn("outbox event {} has no recipients; skipped", event.id());
            return;
        }
        Notification.Category category = "HIGH_RISK".equals(payload.path("category").asText())
                ? Notification.Category.HIGH_RISK
                : Notification.Category.INFO;
        String title = payload.path("title").asText("");
        String body = payload.path("body").asText("");
        String aggregateType = payload.path("aggregateType").isTextual()
                ? payload.path("aggregateType").asText() : null;
        UUID aggregateId = payload.path("aggregateId").isTextual()
                ? UUID.fromString(payload.path("aggregateId").asText()) : null;
        List<UUID> recipientIds = new ArrayList<>(recipients.size());
        for (JsonNode node : recipients) {
            recipientIds.add(UUID.fromString(node.asText()));
        }
        for (UUID recipient : recipientIds) {
            boolean inserted = notifications.insertIgnored(UUID.randomUUID(), event.companyId(),
                    event.id(), recipient, category.name(), title, body, aggregateType, aggregateId);
            if (!inserted) {
                log.debug("notification for event {} recipient {} already exists", event.id(), recipient);
            }
        }
    }
}
