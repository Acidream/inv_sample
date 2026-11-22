package com.example.savings;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SavingsApplication {

    public static void main(String[] args) {
        SpringApplication.run(SavingsApplication.class, args);
    }
}
