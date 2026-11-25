package com.example.investments.adapter.messaging;

import com.example.events.broker.v1.BrokerExecution;
import com.example.events.currentaccount.v1.CurrentAccountEvent;
import com.example.events.investments.v1.InvestmentCommand;
import com.example.events.payments.v1.PaymentInstruction;
import com.example.events.payments.v1.TransferEvent;
import com.example.investments.application.InvestmentService;
import com.example.investments.application.MoneyMovements;
import com.example.investments.application.TradingDesk;
import com.example.platform.messaging.WireValues;
import com.google.protobuf.InvalidProtocolBufferException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Everything the investment service consumes: its own command log, the transfer service's
 * instructions and outcomes, the current account's unblocking, and the broker's reports. Offsets are
 * committed after the transaction, so a crash causes a redelivery, recognised by message id.
 *
 * <p>An event of a type this service doesn't know is skipped: another team adding one is an additive
 * contract change (ADR 0005).
 */
@Component
class InvestmentListeners {

    private static final Logger log = LoggerFactory.getLogger(InvestmentListeners.class);

    private final InvestmentService investments;
    private final MoneyMovements money;
    private final TradingDesk trading;

    InvestmentListeners(InvestmentService investments, MoneyMovements money, TradingDesk trading) {
        this.investments = investments;
        this.money = money;
        this.trading = trading;
    }

    @KafkaListener(topics = "${investments.topics.commands}")
    void onCommand(ConsumerRecord<String, byte[]> record) throws InvalidProtocolBufferException {
        var message = InvestmentCommand.parseFrom(record.value());
        investments.apply(WireFormat.commandId(message.getCommandId()), WireFormat.command(message));
    }

    @KafkaListener(topics = "${investments.topics.instructions}")
    void onInstruction(ConsumerRecord<String, byte[]> record) throws InvalidProtocolBufferException {
        money.apply(WireFormat.instruction(PaymentInstruction.parseFrom(record.value())));
    }

    @KafkaListener(topics = "${investments.topics.transfer-events}")
    void onTransferEvent(ConsumerRecord<String, byte[]> record) throws InvalidProtocolBufferException {
        var outcome = WireFormat.outcome(TransferEvent.parseFrom(record.value()));
        if (outcome == null) {
            log.debug("Ignoring a transfer event of a newer type");
            return;
        }
        money.outcome(outcome);
    }

    @KafkaListener(topics = "${investments.topics.current-account-events}")
    void onCurrentAccountEvent(ConsumerRecord<String, byte[]> record) throws InvalidProtocolBufferException {
        var event = CurrentAccountEvent.parseFrom(record.value());
        if (event.getPayloadCase() != CurrentAccountEvent.PayloadCase.CURRENT_ACCOUNT_UNBLOCKED) {
            return;
        }
        money.accountUnblocked(WireValues.id(event.getEventId(), "event_id", WireFormat.MAX_MESSAGE_ID_LENGTH),
                WireValues.uuid(event.getAccountId(), "account_id"),
                WireFormat.occurredAt(event.hasOccurredAt(), event.getOccurredAt()));
    }

    @KafkaListener(topics = "${investments.topics.broker-executions}")
    void onBrokerExecution(ConsumerRecord<String, byte[]> record) throws InvalidProtocolBufferException {
        var report = WireFormat.report(BrokerExecution.parseFrom(record.value()));
        if (report == null) {
            log.debug("Ignoring a broker report of a newer type");
            return;
        }
        trading.apply(report);
    }
}
