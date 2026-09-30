package com.company.scheduler.agent.notify;

import com.company.scheduler.agent.AgentJson;
import com.company.scheduler.agent.event.OutboxRepository;
import com.company.scheduler.shared.error.BusinessException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 站内通知入口。通知不在业务事务内直接写 notification 表，而是追加
 * notification.inbox Outbox 事件（与业务写入同事务），由分发器在事务提交后投递。
 * 事件 id 使用调用方给定的确定性 businessEventId：同一业务事件重试不会产生重复通知。
 */
@Service
public class NotificationService {

    public static final String EVENT_TYPE = "notification.inbox";

    private final OutboxRepository outbox;
    private final TransactionTemplate transactions;

    public NotificationService(OutboxRepository outbox, TransactionTemplate transactions) {
        this.outbox = outbox;
        this.transactions = transactions;
    }

    /**
     * 追加一条站内通知事件。默认 REQUIRED 传播：调用者有事务则加入（业务回滚则通知消失），
     * 无事务时自开短事务（仅用于非业务关键场景）。
     */
    public UUID publishInboxEvent(UUID businessEventId, UUID companyId, Notification.Category category,
            String title, String body, List<UUID> recipientMemberIds,
            String aggregateType, UUID aggregateId) {
        if (companyId == null || recipientMemberIds == null || recipientMemberIds.isEmpty()) {
            throw new IllegalArgumentException("notification requires companyId and at least one recipient");
        }
        for (UUID recipient : recipientMemberIds) {
            if (recipient == null) {
                throw new IllegalArgumentException("recipient member id must not be null");
            }
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("category", category.name());
        payload.put("title", requireLength(title, 200));
        payload.put("body", requireLength(body, 2000));
        payload.put("aggregateType", aggregateType);
        payload.put("aggregateId", aggregateId == null ? null : aggregateId.toString());
        payload.put("recipientMemberIds", recipientMemberIds.stream().map(UUID::toString).toList());
        String payloadJson = AgentJson.toJson(payload);
        transactions.executeWithoutResult(status ->
                outbox.append(businessEventId, companyId, EVENT_TYPE, aggregateType, aggregateId, payloadJson));
        return businessEventId;
    }

    private String requireLength(String value, int max) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "NOTIFICATION_TEXT_REQUIRED",
                    "通知标题与正文不能为空");
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
