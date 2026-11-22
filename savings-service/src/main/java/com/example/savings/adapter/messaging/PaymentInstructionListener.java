package com.example.savings.adapter.messaging;

import com.example.events.payments.v1.PaymentInstruction;
import com.example.savings.application.PaymentInstructionHandler;
import com.google.protobuf.InvalidProtocolBufferException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The offset is committed only after {@link PaymentInstructionHandler#handle} returned, i.e. after
 * the database transaction committed. A crash in between causes a redelivery, which the handler
 * recognises by event id.
 */
@Component
class PaymentInstructionListener {

    private final PaymentInstructionHandler handler;

    PaymentInstructionListener(PaymentInstructionHandler handler) {
        this.handler = handler;
    }

    @KafkaListener(topics = "${savings.topics.payment-instructions}")
    void onMessage(ConsumerRecord<String, byte[]> record) throws InvalidProtocolBufferException {
        var message = PaymentInstruction.parseFrom(record.value());
        handler.handle(PaymentInstructionTranslator.translate(message));
    }
}
