package com.example.savings.adapter.web;

import com.example.savings.adapter.web.AccountDtos.ProductsResponse;
import com.example.savings.application.ProductService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The savings products a customer can open today, each with the terms of its current version. */
@RestController
@RequestMapping("/products")
class ProductController {

    private final ProductService products;

    ProductController(ProductService products) {
        this.products = products;
    }

    @GetMapping
    ProductsResponse onSale() {
        return ProductsResponse.from(products.onSale());
    }
}
