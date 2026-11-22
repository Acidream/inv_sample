package com.example.savings.application.port;

import com.example.savings.domain.Hold;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface Holds {

    void add(Hold hold);

    Hold get(UUID id);

    Optional<Hold> findByRequestId(String requestId);

    List<UUID> idsDueForExpiry(Instant now, int limit);
}
