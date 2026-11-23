package com.example.savings.application.port;

import com.example.savings.domain.SavingsProduct;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The product catalogue (ADR 0017). Product versions are reference data, published by database
 * migrations and never changed. A product's current version is the latest one valid at that moment.
 */
public interface Products {

    /** The current version of the product, empty if there is no such product or it isn't sold. */
    Optional<SavingsProduct> onSale(String code, Instant at);

    /** The current version of every product on sale, ordered by code. */
    List<SavingsProduct> allOnSale(Instant at);
}
