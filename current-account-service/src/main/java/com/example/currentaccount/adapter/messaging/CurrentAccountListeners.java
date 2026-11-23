package com.example.currentaccount.adapter.messaging;

import com.example.currentaccount.application.CurrentAccountService;
import com.example.events.currentaccount.v1.CurrentAccountCommand;
import com.example.events.payments.v1.CurrentAccountInstruction;
import com.google.protobuf.InvalidProtocolBufferException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Everything the current account consumes: its own command log and the transfer service's
 * instructions. Offsets are committed after the transaction, so a crash causes a redelivery,
 * recognised by message id.
 */
@Component
class CurrentAccountListeners {

    private final CurrentAccountService accounts;

    CurrentAccountListeners(CurrentAccountService accounts) {
        this.accounts = accounts;
    }

    @KafkaListener(topics = "${current-account.topics.commands}")
    void onCommand(ConsumerRecord<String, byte[]> record) throws InvalidProtocolBufferException {
        var message = CurrentAccountCommand.parseFrom(record.value());
        accounts.apply(WireFormat.commandId(message.getCommandId()), WireFormat.command(message));
    }

    @KafkaListener(topics = "${current-account.topics.instructions}")
    void onInstruction(ConsumerRecord<String, byte[]> record) throws InvalidProtocolBufferException {
        accounts.apply(WireFormat.instruction(CurrentAccountInstruction.parseFrom(record.value())));
    }
}
