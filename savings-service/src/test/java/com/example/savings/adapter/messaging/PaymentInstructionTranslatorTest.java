package com.example.savings.adapter.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.example.events.common.v1.Money;
import com.example.events.payments.v1.PaymentInstruction;
import com.example.events.payments.v1.TransferFromCurrentAccount;
import com.example.events.payments.v1.TransferReturned;
import com.example.platform.messaging.InvalidMessageException;
import com.example.savings.application.PaymentInstruction.TransferIn;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Everything the database would refuse is rejected here, so it is dead-lettered instead of retried forever. */
class PaymentInstructionTranslatorTest {

    private static final String ACCOUNT_ID = UUID.randomUUID().toString();

    @Test
    void translate_ValidDeposit_ReturnsTransferIn() {
        var translated = PaymentInstructionTranslator.translate(deposit("e-1", "tr-1", "99999999999999999.99"));

        assertThat(translated).isEqualTo(new TransferIn("e-1", UUID.fromString(ACCOUNT_ID), "tr-1",
                com.example.savings.domain.Money.of("99999999999999999.99")));
    }

    @Test
    void translate_IdsOfColumnLength_AreAccepted() {
        var id = "x".repeat(PaymentInstructionTranslator.MAX_ID_LENGTH);

        assertThat(PaymentInstructionTranslator.translate(deposit(id, id, "1.00")).eventId()).isEqualTo(id);
    }

    @Test
    void translate_EventIdLongerThanColumn_ThrowsInvalidMessage() {
        var tooLong = "x".repeat(PaymentInstructionTranslator.MAX_ID_LENGTH + 1);

        assertThatExceptionOfType(InvalidMessageException.class)
                .isThrownBy(() -> PaymentInstructionTranslator.translate(deposit(tooLong, "tr-1", "1.00")))
                .withMessageContaining("event_id");
    }

    @Test
    void translate_TransferIdLongerThanColumn_ThrowsInvalidMessage() {
        var tooLong = "x".repeat(PaymentInstructionTranslator.MAX_ID_LENGTH + 1);

        assertThatExceptionOfType(InvalidMessageException.class)
                .isThrownBy(() -> PaymentInstructionTranslator.translate(deposit("e-1", tooLong, "1.00")))
                .withMessageContaining("transfer_id");
        assertThatExceptionOfType(InvalidMessageException.class)
                .isThrownBy(() -> PaymentInstructionTranslator.translate(envelope("e-1")
                        .setTransferReturned(TransferReturned.newBuilder().setTransferId(tooLong)).build()))
                .withMessageContaining("transfer_id");
    }

    @Test
    void translate_NulCharacterInId_ThrowsInvalidMessage() {
        assertThatExceptionOfType(InvalidMessageException.class)
                .isThrownBy(() -> PaymentInstructionTranslator.translate(deposit("e-1", "tr\u00001", "1.00")))
                .withMessageContaining("NUL");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "1e5",                       // exponent notation
        "-1.00",                     // sign
        "+1.00",
        "1.001",                     // sub-cent
        ".50",
        "1,00",
        "100000000000000000.00",     // 18 integer digits: does not fit numeric(19, 2)
        "0.00"                       // not positive
    })
    void translate_AmountNotPlainPositiveOrTooLarge_ThrowsInvalidMessage(String amount) {
        assertThatExceptionOfType(InvalidMessageException.class)
                .isThrownBy(() -> PaymentInstructionTranslator.translate(deposit("e-1", "tr-1", amount)));
    }

    private static PaymentInstruction deposit(String eventId, String transferId, String amount) {
        return envelope(eventId)
                .setTransferFromCurrentAccount(TransferFromCurrentAccount.newBuilder()
                        .setTransferId(transferId)
                        .setAmount(Money.newBuilder().setAmount(amount).setCurrency("EUR")))
                .build();
    }

    private static PaymentInstruction.Builder envelope(String eventId) {
        return PaymentInstruction.newBuilder().setEventId(eventId).setAccountId(ACCOUNT_ID);
    }
}
