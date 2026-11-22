package com.example.savings.application;

import com.example.savings.application.port.InterestAccruals;
import com.example.savings.application.port.Ledger;
import com.example.savings.application.port.PostingPublisher;
import com.example.savings.domain.LedgerEntry;
import com.example.savings.domain.Posting;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The one way to book money. Every customer ledger entry and every interest accrual goes through
 * here, so each is matched by a balanced posting for Finance in the same transaction: the customer
 * ledger and the bank's books can't drift apart.
 */
@Component
public class Bookkeeping {

    private final Ledger ledger;
    private final InterestAccruals accruals;
    private final PostingPublisher postings;
    private final SavingsPolicy policy;
    private final Clock clock;

    public Bookkeeping(Ledger ledger, InterestAccruals accruals, PostingPublisher postings, SavingsPolicy policy,
            Clock clock) {
        this.ledger = ledger;
        this.accruals = accruals;
        this.postings = postings;
        this.policy = policy;
        this.clock = clock;
    }

    public void book(LedgerEntry entry) {
        ledger.append(entry);
        postings.publish(Posting.of(entry, policy.businessZone()));
    }

    /** Days without interest (zero or negative balance) are recorded for the audit trail but not posted. */
    public void accrue(UUID accountId, LocalDate day, BigDecimal interest) {
        accruals.record(accountId, day, interest);
        if (interest.signum() > 0) {
            postings.publish(Posting.interestAccrual(accountId, day, interest, clock.instant()));
        }
    }
}
