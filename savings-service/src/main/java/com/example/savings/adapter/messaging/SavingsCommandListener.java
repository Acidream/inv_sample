package com.example.savings.adapter.messaging;

import com.example.events.savings.v1.SavingsCommand;
import com.example.savings.application.order.OrderService;
import com.google.protobuf.InvalidProtocolBufferException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Applies savings' command log (ADR 0019). The offset is committed after the transaction. */
@Component
class SavingsCommandListener {

    private final OrderService orders;

    SavingsCommandListener(OrderService orders) {
        this.orders = orders;
    }

    @KafkaListener(topics = "${savings.topics.savings-commands}")
    void onMessage(ConsumerRecord<String, byte[]> record) throws InvalidProtocolBufferException {
        var command = SavingsCommandTranslator.translate(SavingsCommand.parseFrom(record.value()));
        orders.place(command.commandId(), command.request());
    }
}
