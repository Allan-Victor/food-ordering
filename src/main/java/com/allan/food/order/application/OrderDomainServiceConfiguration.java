package com.allan.food.order.application;

import com.allan.food.order.domain.OrderDomainService;
import com.allan.food.order.domain.OrderDomainServiceImpl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires domain-layer objects into the Spring context without annotating them.
 *
 * <p><b>Why this class exists.</b> {@link OrderDomainServiceImpl} is intentionally
 * free of {@code @Service} or any other Spring annotation — the domain layer has
 * no framework imports. But {@link CreateOrderService} depends on
 * {@link OrderDomainService} via constructor injection, so Spring needs to know
 * how to provide it. A {@code @Configuration} class in the application layer is
 * the standard seam: it knows about both the domain (it instantiates the impl)
 * and Spring (it declares the bean), so neither layer has to compromise.
 *
 * <p><b>Alternative you'll see elsewhere:</b> annotate {@code OrderDomainServiceImpl}
 * with {@code @Service} directly — one less file, but a framework import bleeds
 * into the domain. The reference takes that shortcut. We don't, because the whole
 * point of the pure-hexagonal style is that the domain compiles and tests with
 * zero framework on the classpath. This file is the price of that property, and
 * it is a cheap price.
 */
@Configuration
class OrderDomainServiceConfiguration {

    @Bean
    OrderDomainService orderDomainService() {
        return new OrderDomainServiceImpl();
    }
}
