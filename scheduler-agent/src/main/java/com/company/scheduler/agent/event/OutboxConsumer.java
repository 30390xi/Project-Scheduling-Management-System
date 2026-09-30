package com.company.scheduler.agent.event;

/** Outbox 消费者。consume 与 processed_event 写入在同一事务，失败整体回滚等待重投。 */
public interface OutboxConsumer {

    /** 消费者唯一名，参与 (consumer_name, event_id) 幂等。 */
    String consumerName();

    void consume(OutboxEvent event);
}
