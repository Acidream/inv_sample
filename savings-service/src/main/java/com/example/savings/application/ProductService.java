package com.example.savings.application;

import com.example.savings.application.port.Products;
import com.example.savings.domain.SavingsProduct;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;

/** What a customer can open today. */
@Service
public class ProductService {

    private final Products products;
    private final Clock clock;

    public ProductService(Products products, Clock clock) {
        this.products = products;
        this.clock = clock;
    }

    public List<SavingsProduct> onSale() {
        return products.allOnSale(clock.instant());
    }
}
