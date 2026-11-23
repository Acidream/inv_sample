package com.example.currentaccount.application.port;

import com.example.currentaccount.domain.IncomingPayment;
import java.util.Optional;

public interface IncomingPayments {

    Optional<IncomingPayment> find(String paymentId);

    void add(IncomingPayment payment);
}
