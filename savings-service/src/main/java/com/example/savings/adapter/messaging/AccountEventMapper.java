package com.example.savings.adapter.messaging;

import com.example.events.savings.v1.AccountOpened;
import com.example.events.savings.v1.InterestPaid;
import com.example.events.savings.v1.MoneyDeposited;
import com.example.events.savings.v1.MoneyWithdrawn;
import com.example.events.savings.v1.WithdrawalRejected;
import com.example.events.savings.v1.WithdrawalReturned;
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
                    .setAnnualRate(e.product().annualRate().toPlainString())
                    .setProductCode(e.product().code())
                    .setProductVersion(e.product().version())
                    .setPayoutFrequency(AccountOpened.PayoutFrequency.valueOf(e.product().payoutFrequency().name()))
                    .setOrderId(e.orderId().toString()));
            case AccountEvent.MoneyDeposited e -> envelope.setMoneyDeposited(MoneyDeposited.newBuilder()
                    .setTransferId(e.transferId())
                    .setAmount(money(e.amount()))
                    .setBalance(money(e.balance())));
            case AccountEvent.MoneyWithdrawn e -> envelope.setMoneyWithdrawn(MoneyWithdrawn.newBuilder()
                    .setTransferId(e.transferId())
                    .setAmount(money(e.amount()))
                    .setBalance(money(e.balance())));
            case AccountEvent.WithdrawalRejected e -> envelope.setWithdrawalRejected(WithdrawalRejected.newBuilder()
                    .setTransferId(e.transferId())
                    .setAmount(money(e.amount()))
                    .setReason(WithdrawalRejected.Reason.valueOf(e.reason().name())));
            case AccountEvent.WithdrawalReturned e -> envelope.setWithdrawalReturned(WithdrawalReturned.newBuilder()
                    .setTransferId(e.transferId())
                    .setAmount(money(e.amount()))
                    .setBalance(money(e.balance())));
            case AccountEvent.InterestPaid e -> envelope.setInterestPaid(InterestPaid.newBuilder()
                    .setPeriod(e.period().label())
                    .setPeriodStart(e.period().start().toString())
                    .setPeriodEnd(e.period().end().toString())
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
