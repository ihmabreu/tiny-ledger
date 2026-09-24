package com.teya.tinyledger.config;

import com.teya.tinyledger.domain.Account;
import com.teya.tinyledger.domain.Money;
import com.teya.tinyledger.service.LedgerService;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.Currency;

/**
 * Populates the in-memory ledger with a small, realistic data set at start-up.
 *
 * <p>Because storage is in memory, a freshly started instance is otherwise completely empty,
 * which makes exploring the API awkward &mdash; every reviewer would have to create an account
 * before they could look at anything. Seeding removes that friction.</p>
 *
 * <p>It is disabled in the test profile so that every automated test tier starts from a known,
 * empty ledger; see {@code application.yml}.</p>
 */
@ApplicationScoped
public class LedgerDataSeeder {

    private static final Logger LOG = Logger.getLogger(LedgerDataSeeder.class);

    private final LedgerService ledgerService;
    private final boolean seedEnabled;

    /**
     * Creates the seeder.
     *
     * @param ledgerService the ledger use cases, used so that seeded data goes through exactly
     *                      the same validation as data created over HTTP
     * @param seedEnabled   whether demo data should be created
     */
    @Inject
    public LedgerDataSeeder(LedgerService ledgerService,
                            @ConfigProperty(name = "ledger.seed.enabled", defaultValue = "true")
                            boolean seedEnabled) {
        this.ledgerService = ledgerService;
        this.seedEnabled = seedEnabled;
    }

    /**
     * Seeds demo accounts and movements once the application is up.
     *
     * @param event the CDI start-up event
     */
    public void onStart(@Observes StartupEvent event) {
        if (!seedEnabled) {
            LOG.info("Demo data seeding is disabled; starting with an empty ledger.");
            return;
        }

        Currency eur = Currency.getInstance("EUR");
        Currency gbp = Currency.getInstance("GBP");

        Account ada = ledgerService.openAccount("Ada Lovelace", eur, Money.zero(eur));
        ledgerService.deposit(ada.id(), Money.of(new BigDecimal("1200.00"), eur), "Salary");
        ledgerService.withdraw(ada.id(), Money.of(new BigDecimal("49.99"), eur), "Bookshop");
        ledgerService.withdraw(ada.id(), Money.of(new BigDecimal("200.00"), eur), "ATM withdrawal");

        Account grace = ledgerService.openAccount("Grace Hopper", gbp,
                Money.of(new BigDecimal("500.00"), gbp));
        ledgerService.deposit(grace.id(), Money.of(new BigDecimal("80.00"), gbp), "Refund");
        ledgerService.withdraw(grace.id(), Money.of(new BigDecimal("300.00"), gbp), "Rent");

        ledgerService.openAccount("Alan Turing", eur, Money.zero(eur));

        LOG.infof("Seeded %d demo accounts.", ledgerService.listAccounts().size());
    }
}
