package com.example.investments.application.port;

import com.example.investments.domain.Posting;
import java.time.Instant;

/**
 * Double-entry postings for Finance (ADR 0014, 0026). Must be called inside the transaction that
 * booked the money: the posting is sent if and only if it commits.
 */
public interface PostingPublisher {

    void publish(Posting posting, Instant at);
}
