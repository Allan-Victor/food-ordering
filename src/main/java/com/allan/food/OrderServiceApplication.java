package com.allan.food;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Slice 1 entry point.
 *
 * Lives in com.allan.food (the root) so component scanning covers the
 * whole tree. Each bounded context is a package beneath it — right now
 * just `order`.
 */
@SpringBootApplication
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
