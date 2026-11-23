package com.example.currentaccount.application.port;

import com.example.currentaccount.domain.TransferAnswer;
import java.util.Optional;
import java.util.UUID;

public interface TransferAnswers {

    Optional<TransferAnswer> find(UUID accountId, String transferId);

    void add(TransferAnswer answer);
}
