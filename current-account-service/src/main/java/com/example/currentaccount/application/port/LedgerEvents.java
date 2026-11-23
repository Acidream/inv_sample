package com.example.currentaccount.application.port;

import com.example.currentaccount.domain.CurrentAccount;
import com.example.currentaccount.domain.TransferAnswer;

/** What the account tells others, on {@code current-account.events.v1}, in the same transaction. */
public interface LedgerEvents {

    void opened(CurrentAccount account);

    void answered(TransferAnswer answer);
}
