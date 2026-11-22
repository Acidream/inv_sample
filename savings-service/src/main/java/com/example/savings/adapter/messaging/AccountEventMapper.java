package com.example.savings.adapter.messaging;

import com.example.events.savings.v1.AccountOpened;
import com.example.events.savings.v1.FundsReleased;
import com.example.events.savings.v1.FundsReserved;
import com.example.events.savings.v1.InterestPaid;
import com.example.events.savings.v1.MoneyDeposited;
import com.example.events.savings.v1.MoneyWithdrawn;
import com.example.events.savings.v1.WithdrawalRejected;
import com.example.savings.domain.AccountEvent;
import com.example.savings.domain.Money;
import com.google.protobuf.Timestamp;
import java.time.Instant;

/** Domain events to the published Protobuf contract. */
final class AccountEventMapper {

    private AccountEventMapper() {
    }

    static com.example.events.savings.v1.AccountEvent toMessage(AccountEvent event, String eventId,
            Instant occurredAt) {
        var envelope = com.example.events.savings.v1.AccountEvent.newBuilder()
                .setEventId(eventId)
                .setOccurredAt(Timestamp.newBuilder()
                        .setSeconds(occurredAt.getEpochSecond())
                        .setNanos(occurredAt.getNano()))
                .setAccountId(event.accountId().toString());

        switch (event) {
            case AccountEvent.AccountOpened e -> envelope.setAccountOpened(AccountOpened.newBuilder()
                    .setCustomerId(e.customerId().toString())
                    .setAnnualRate(e.annualRate().toPlainString()));
            case AccountEvent.MoneyDeposited e -> envelope.setMoneyDeposited(MoneyDeposited.newBuilder()
                    .setPaymentId(e.paymentId())
                    .setAmount(money(e.amount()))
                    .setBalance(money(e.balance())));
            case AccountEvent.FundsReserved e -> envelope.setFundsReserved(FundsReserved.newBuilder()
                    .setRequestId(e.requestId())
                    .setAmount(money(e.amount()))
                    .setAvailableBalance(money(e.availableBalance())));
            case AccountEvent.WithdrawalRejected e -> envelope.setWithdrawalRejected(WithdrawalRejected.newBuilder()
                    .setRequestId(e.requestId())
                    .setAmount(money(e.amount()))
                    .setReason(WithdrawalRejected.Reason.valueOf(e.reason().name())));
            case AccountEvent.MoneyWithdrawn e -> envelope.setMoneyWithdrawn(MoneyWithdrawn.newBuilder()
                    .setRequestId(e.requestId())
                    .setAmount(money(e.amount()))
                    .setBalance(money(e.balance())));
            case AccountEvent.FundsReleased e -> envelope.setFundsReleased(FundsReleased.newBuilder()
                    .setRequestId(e.requestId())
                    .setAmount(money(e.amount()))
                    .setReason(FundsReleased.Reason.valueOf(e.reason().name())));
            case AccountEvent.InterestPaid e -> envelope.setInterestPaid(InterestPaid.newBuilder()
                    .setPeriod(e.period().toString())
                    .setAmount(money(e.amount()))
                    .setBalance(money(e.balance())));
        }
        return envelope.build();
    }

    private static com.example.events.common.v1.Money money(Money money) {
        return com.example.events.common.v1.Money.newBuilder()
                .setAmount(money.toString())
                .setCurrency(Money.CURRENCY)
                .build();
    }
}
