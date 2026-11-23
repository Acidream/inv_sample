package com.example.currentaccount;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CurrentAccountApplication {

    public static void main(String[] args) {
        SpringApplication.run(CurrentAccountApplication.class, args);
    }
}
