package com.example.savings.application.port;

import com.example.savings.domain.Posting;

/** Sends postings to Finance. Must run in the transaction of the booking it describes (outbox). */
public interface PostingPublisher {

    void publish(Posting posting);
}
