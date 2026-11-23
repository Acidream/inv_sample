package com.example.currentaccount.adapter.messaging;

import com.example.currentaccount.application.Command;
import com.example.currentaccount.application.Instruction;
import com.example.currentaccount.domain.CurrentAccount;
import com.example.currentaccount.domain.Money;
import com.example.currentaccount.domain.TransferAnswer.Direction;
import com.example.events.currentaccount.v1.BlockAccount;
import com.example.events.currentaccount.v1.CurrentAccountCommand;
import com.example.events.currentaccount.v1.OpenCurrentAccount;
import com.example.events.currentaccount.v1.ReceivePayment;
import com.example.events.currentaccount.v1.UnblockAccount;
import com.example.events.payments.v1.CurrentAccountInstruction;
import com.example.platform.messaging.InvalidMessageException;
import com.example.platform.messaging.WireValues;
import com.google.protobuf.Timestamp;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Anti-corruption layer for the wire formats: validates what comes in and builds what goes out.
 * Anything malformed throws {@link InvalidMessageException}, which dead-letters the message; the
 * technical checks are {@link WireValues}, the business ones (EUR, positive amounts) are here.
 */
final class WireFormat {

    /** {@code transfer_answer.transfer_id} and {@code incoming_payment.payment_id}, {@code varchar(100)}. */
    static final int MAX_REFERENCE_LENGTH = 100;
    /** {@code processed_message.message_id varchar(200)}, less the prefix. */
    static final int MAX_MESSAGE_ID_LENGTH = 180;
    static final int MAX_REASON_LENGTH = 300;

    private WireFormat() {
    }

    // --- commands ---------------------------------------------------------------------------------

    static Command command(CurrentAccountCommand message) {
        var accountId = uuid(message.getAccountId(), "account_id");
        var at = instant(message.hasOccurredAt() ? message.getOccurredAt() : null);
        return switch (message.getPayloadCase()) {
            case OPEN_CURRENT_ACCOUNT -> {
                var open = message.getOpenCurrentAccount();
                yield new Command.Open(accountId, uuid(open.getCustomerId(), "customer_id"),
                        overdraftLimit(open.getOverdraftLimit()), at);
            }
            case RECEIVE_PAYMENT -> {
                var payment = message.getReceivePayment();
                yield new Command.ReceivePayment(accountId, reference(payment.getPaymentId(), "payment_id"),
                        money(payment.getAmount()), at);
            }
            case BLOCK_ACCOUNT -> new Command.Block(accountId, reason(message.getBlockAccount().getReason()), at);
            case UNBLOCK_ACCOUNT -> new Command.Unblock(accountId, at);
            case PAYLOAD_NOT_SET -> throw new InvalidMessageException("Command without payload");
        };
    }

    static CurrentAccountCommand toMessage(String commandId, Command command) {
        var message = CurrentAccountCommand.newBuilder()
                .setCommandId(commandId)
                .setOccurredAt(timestamp(command.acceptedAt()))
                .setAccountId(command.accountId().toString());
        switch (command) {
            case Command.Open open -> message.setOpenCurrentAccount(OpenCurrentAccount.newBuilder()
                    .setCustomerId(open.customerId().toString())
                    .setOverdraftLimit(eur(open.overdraftLimit())));
            case Command.ReceivePayment payment -> message.setReceivePayment(ReceivePayment.newBuilder()
                    .setPaymentId(payment.paymentId())
                    .setAmount(money(payment.amount())));
            case Command.Block block -> message.setBlockAccount(BlockAccount.newBuilder().setReason(block.reason()));
            case Command.Unblock unblock -> message.setUnblockAccount(UnblockAccount.getDefaultInstance());
        }
        return message.build();
    }

    static String commandId(String value) {
        return id(value, "command_id", MAX_MESSAGE_ID_LENGTH);
    }

    // --- instructions -----------------------------------------------------------------------------

    static Instruction instruction(CurrentAccountInstruction message) {
        var messageId = id(message.getEventId(), "event_id", MAX_MESSAGE_ID_LENGTH);
        var accountId = uuid(message.getAccountId(), "account_id");
        var at = instant(message.hasOccurredAt() ? message.getOccurredAt() : null);
        return switch (message.getPayloadCase()) {
            case DEBIT_CURRENT_ACCOUNT -> new Instruction(messageId, accountId,
                    reference(message.getDebitCurrentAccount().getTransferId(), "transfer_id"), Direction.DEBIT,
                    money(message.getDebitCurrentAccount().getAmount()), at);
            case CREDIT_CURRENT_ACCOUNT -> new Instruction(messageId, accountId,
                    reference(message.getCreditCurrentAccount().getTransferId(), "transfer_id"), Direction.CREDIT,
                    money(message.getCreditCurrentAccount().getAmount()), at);
            case PAYLOAD_NOT_SET -> throw new InvalidMessageException("Instruction " + messageId + " has no payload");
        };
    }

    // --- values -----------------------------------------------------------------------------------

    static Money money(com.example.events.common.v1.Money money) {
        var amount = amount(money);
        if (amount.signum() <= 0) {
            throw new InvalidMessageException("Amount must be positive: " + money.getAmount());
        }
        return new Money(amount);
    }

    static com.example.events.common.v1.Money money(Money money) {
        return eur(money.amount());
    }

    static Timestamp timestamp(Instant instant) {
        return WireValues.timestamp(instant);
    }

    private static BigDecimal overdraftLimit(com.example.events.common.v1.Money money) {
        return CurrentAccount.overdraftLimit(amount(money));
    }

    private static BigDecimal amount(com.example.events.common.v1.Money money) {
        if (!Money.CURRENCY.equals(money.getCurrency())) {
            throw new InvalidMessageException("Unsupported currency: " + money.getCurrency());
        }
        return WireValues.plainAmount(money.getAmount());
    }

    private static com.example.events.common.v1.Money eur(BigDecimal amount) {
        return com.example.events.common.v1.Money.newBuilder()
                .setAmount(amount.setScale(Money.SCALE).toPlainString())
                .setCurrency(Money.CURRENCY)
                .build();
    }

    private static UUID uuid(String value, String field) {
        return WireValues.uuid(value, field);
    }

    private static Instant instant(Timestamp timestamp) {
        return WireValues.instant(timestamp, "occurred_at");
    }

    private static String reference(String value, String field) {
        return WireValues.id(value, field, MAX_REFERENCE_LENGTH);
    }

    private static String reason(String value) {
        return WireValues.text(WireValues.required(value, "reason"), MAX_REASON_LENGTH);
    }

    private static String id(String value, String field, int maxLength) {
        return WireValues.id(value, field, maxLength);
    }
}
