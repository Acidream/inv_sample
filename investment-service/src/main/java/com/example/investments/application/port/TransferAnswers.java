package com.example.investments.application.port;

import com.example.investments.domain.TransferAnswer;
import java.util.Optional;
import java.util.UUID;

public interface TransferAnswers {

    Optional<TransferAnswer> find(UUID portfolioId, String transferId, TransferAnswer.Kind kind);

    void add(TransferAnswer answer);
}
