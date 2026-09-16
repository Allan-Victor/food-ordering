package com.allan.food.payment.adapter.out.persistence;

import com.allan.food.payment.application.port.out.PaymentPersistencePort;
import com.allan.food.payment.domain.model.CustomerCredit;
import com.allan.food.payment.domain.model.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.UUID;

/**
 * Seeds customer balances for local exploration.
 *
 * <p><b>Seeded through the port, not through {@code data.sql}.</b> An SQL script writes rows the domain has
 * never seen — it can produce a negative balance, a currency the aggregate would reject, a state that could not
 * have been reached legitimately. Going through {@code CustomerCredit.open} means the seed data is subject to
 * the same invariants as production data, and the seeding path exercises the same adapter the application uses.
 *
 * <p><b>Profile-guarded.</b> Seed data that runs in every environment is a defect waiting for the wrong
 * environment. {@code @Profile("dev")} keeps it out of tests, where it would fight fixtures, and out of any
 * real deployment.
 *
 * <p>Two customers, deliberately: one who can afford several orders, and one who cannot afford any. The second
 * is what makes the compensatable step's failure path reachable without editing code.
 */
@Configuration
@Profile("dev")
class PaymentSeedConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PaymentSeedConfiguration.class);

    /** Matches the customer id used in the sample requests. */
    static final UUID WEALTHY_CUSTOMER = UUID.fromString("99999999-9999-9999-9999-999999999999");

    /** Funded with less than a single Rolex costs, so any order from them fails payment. */
    static final UUID BROKE_CUSTOMER = UUID.fromString("88888888-8888-8888-8888-888888888888");

    @Bean
    ApplicationRunner seedCustomerCredits(PaymentPersistencePort persistence) {
        return args -> {
            seed(persistence, WEALTHY_CUSTOMER, "500000");
            seed(persistence, BROKE_CUSTOMER, "1000");
        };
    }

    /** Idempotent: re-running against an existing balance leaves it alone rather than resetting it. */
    private void seed(PaymentPersistencePort persistence, UUID customerId, String balance) {
        if (persistence.findCredit(customerId).isPresent()) {
            return;
        }
        persistence.saveCredit(CustomerCredit.open(customerId, Money.of(balance, "UGX")));
        log.info("Seeded customer {} with {} UGX", customerId, balance);
    }
}
