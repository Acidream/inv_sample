package com.example.savings.application.port;

import com.example.savings.domain.Transfer;
import java.util.Optional;

/** Every transfer between the customer's current account and savings, by transfer id. */
public interface Transfers {

    Optional<Transfer> find(String transferId);

    void add(Transfer transfer);
}
