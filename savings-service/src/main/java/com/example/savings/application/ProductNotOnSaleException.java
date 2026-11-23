package com.example.savings.application;

/** An account was requested for a product that doesn't exist or is no longer sold. */
public class ProductNotOnSaleException extends RuntimeException {

    public ProductNotOnSaleException(String productCode) {
        super("Product " + productCode + " is not on sale");
    }
}
