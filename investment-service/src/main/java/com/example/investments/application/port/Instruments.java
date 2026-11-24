package com.example.investments.application.port;

import com.example.investments.domain.Instrument;
import java.util.List;
import java.util.Optional;

/** The instrument catalogue, reference data from Flyway (ADR 0026). */
public interface Instruments {

    Optional<Instrument> find(String isin);

    List<Instrument> all();
}
